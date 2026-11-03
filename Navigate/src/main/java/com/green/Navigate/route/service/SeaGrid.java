package com.green.Navigate.route.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


// 바다/육지 격자
//
// "이 좌표가 바다인가?" 를 답해 주는 클래스. A* 가 바다 칸만 지나가게 해서 육지를 피한다.
//
// 격자는 MTIS 해양격자와 같은 0.025° (약 2.8 × 2.3km) 칸이다.
// 교통량·혼잡 예보·사고위험 예보가 모두 이 격자로 들어오기 때문에,
// 칸을 맞춰 두면 나중에 위험도를 그대로 가져다 쓸 수 있다.
//
// 자료 파일: resources/sea/sea_grid.bin (88KB)
//   - OpenStreetMap 해안선으로 만든 110m 격자를 2.5km 칸으로 줄인 것 (tools/make_sea_grid.py)
//   - 1바이트 = 1칸, 값은 그 칸의 "바다 비율" 0~255 (255 = 전부 바다)
@Slf4j
@Component
public class SeaGrid {

  // ---------- 격자 설정 (tools/make_sea_grid.py 와 같은 값이어야 함) ----------

  // 한 칸의 크기 (도). 약 500m.
  //
  // 처음에는 MTIS 해양격자와 같은 0.025°(2.5km) 로 만들었는데 칸이 너무 커서 문제가 됐다.
  // 칸의 30% 만 바다여도 통과 가능으로 보니 나머지 1.7km 가 육지일 수 있고,
  // 경로는 칸 한가운데를 잇기 때문에 그 중심점이 육지에 놓이기도 했다.
  // 실제 해안선으로 재 보니 경로의 3~16% 가 육지 위를 지나갔다.
  //
  // 0.025 / 0.005 = 5 라서 MTIS 칸 하나가 이 격자 5x5 로 딱 나뉜다.
  // 즉 4단계에서 MTIS 교통량·예보를 가져올 때는 칸 번호를 5로 나누기만 하면 되므로,
  // 격자를 잘게 만들면서도 MTIS 와의 정렬은 그대로 유지된다.
  public static final double CELL = 0.005;

  // 격자가 덮는 범위의 남서쪽 끝 (0행 0열의 위치)
  public static final double LAT0 = 32.0;
  public static final double LON0 = 124.0;

  // 격자 크기
  public static final int ROWS = 1400;   // 위도 32.0 ~ 39.0
  public static final int COLS = 1600;   // 경도 124.0 ~ 132.0

  // 배가 지나갈 수 있다고 볼 최소 바다 비율
  // 0.8 = 칸의 80% 이상이 바다여야 통과 가능
  //
  // 왜 이렇게 높게 잡는가: 경로는 칸과 칸을 직선으로 잇기 때문에, 칸에 육지가 섞여 있으면
  // 그 직선이 육지 쪽을 스치고 지나간다. 기준을 0.5 로 뒀을 때 실제 해안선 기준으로
  // 경로의 1.2% 가 육지 위를 지났다. 0.8 로 올리면 칸에 남는 육지가 100m 안쪽이 된다.
  // 화물선·유조선은 큰 배라 연안에 바짝 붙지 않으므로 넉넉히 잡아도 무리가 없다.
  private static final double MIN_SEA = 0.8;

  // 항구에서 가장 가까운 바다 칸을 찾을 때 몇 칸까지 둘러볼지 (30칸 = 약 15km)
  //
  // 마산항은 만 안쪽이라 큰 바다까지 7.1km 떨어져 있어 넉넉해야 한다.
  // 반대로 너무 넓히면 한강 내륙의 서울항(바다까지 29km)까지 이어져 버리므로 15km 로 둔다.
  private static final int SEARCH_RANGE = 30;

  // 이웃 8방향 {행 변화, 열 변화}
  private static final int[][] NEIGHBORS = {
      {-1, 0}, {1, 0}, {0, -1}, {0, 1},
      {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
  };


  // 격자 자료. 칸 하나당 1바이트. grid[행 * COLS + 열] 로 꺼낸다.
  private byte[] grid;

  // 가장 큰 바다 덩어리에 속한 칸인가. (국내 해역 본체)
  //
  // 2.5km 칸으로 줄이다 보면 입구가 좁은 만(灣)이 바깥 바다와 끊어져 작은 섬처럼 남는다.
  // 예: 마산만은 2칸짜리로 떨어져 나와, 거기서 출발하면 어디로도 갈 수 없다.
  // 그래서 항구를 바다 칸으로 옮길 때는 "큰 바다에 이어진 칸"만 고른다.
  private boolean[] mainSea;


  // 서버가 시작될 때 한 번만 파일을 읽어 둔다.
  // @PostConstruct = 스프링이 이 객체를 만든 직후에 자동으로 실행하는 메서드
  @PostConstruct
  public void load() {
    try (InputStream in = new ClassPathResource("sea/sea_grid.bin").getInputStream()) {
      grid = in.readAllBytes();
    } catch (IOException e) {
      log.error("바다 격자를 읽지 못했습니다 (resources/sea/sea_grid.bin)", e);
      grid = new byte[ROWS * COLS];   // 전부 육지 취급 — 경로를 못 찾게 되지만 서버는 뜬다
      return;
    }

    if (grid.length != ROWS * COLS) {
      log.error("바다 격자 크기가 맞지 않습니다: {} (기대값 {})", grid.length, ROWS * COLS);
      grid = new byte[ROWS * COLS];
      return;
    }

    // 통과 가능한 칸이 몇 개인지 세어서 로그에 남긴다 (제대로 읽혔는지 확인용)
    int navigable = 0;
    for (int i = 0; i < grid.length; i++) {
      if (seaFraction(i) >= MIN_SEA) {
        navigable++;
      }
    }

    findMainSea();
    int main = 0;
    for (boolean b : mainSea) {
      if (b) {
        main++;
      }
    }

    log.info("바다 격자 준비 완료: {}x{} 칸 중 통과 가능 {}칸 (큰 바다에 이어진 칸 {}개)",
        ROWS, COLS, navigable, main);
  }


  // 서로 이어진 바다 칸 덩어리를 모두 찾아, 가장 큰 덩어리를 표시해 둔다.
  //
  // 찾는 방법(너비 우선 탐색): 아직 표시 안 된 바다 칸에서 시작해 이웃을 타고 번져 나간다.
  // 더 번질 곳이 없으면 그게 덩어리 하나. 이걸 격자 전체에 대해 반복한다.
  // 이동 규칙은 A* 와 같아야 하므로, 대각선은 양옆 두 칸이 모두 바다일 때만 이어진 것으로 본다.
  private void findMainSea() {

    int[] group = new int[ROWS * COLS];
    Arrays.fill(group, -1);          // -1 = 아직 어느 덩어리에도 안 들어감

    List<Integer> groupSizes = new ArrayList<>();
    int[] queue = new int[ROWS * COLS];

    for (int start = 0; start < group.length; start++) {
      if (group[start] >= 0 || seaFraction(start) < MIN_SEA) {
        continue;
      }

      int id = groupSizes.size();
      int head = 0;
      int tail = 0;
      queue[tail++] = start;
      group[start] = id;
      int count = 0;

      while (head < tail) {
        int cell = queue[head++];
        count++;
        int row = cell / COLS;
        int col = cell % COLS;

        for (int[] d : NEIGHBORS) {
          int nextRow = row + d[0];
          int nextCol = col + d[1];
          if (!navigable(nextRow, nextCol)) {
            continue;
          }
          // 대각선은 양옆 두 칸도 바다여야 지나갈 수 있다 (섬 모서리를 비스듬히 뚫지 않게)
          if (d[0] != 0 && d[1] != 0
              && (!navigable(nextRow, col) || !navigable(row, nextCol))) {
            continue;
          }
          int next = nextRow * COLS + nextCol;
          if (group[next] < 0) {
            group[next] = id;
            queue[tail++] = next;
          }
        }
      }
      groupSizes.add(count);
    }

    // 가장 큰 덩어리를 고른다.
    int biggest = -1;
    int biggestSize = -1;
    for (int i = 0; i < groupSizes.size(); i++) {
      if (groupSizes.get(i) > biggestSize) {
        biggestSize = groupSizes.get(i);
        biggest = i;
      }
    }

    mainSea = new boolean[group.length];
    for (int i = 0; i < group.length; i++) {
      mainSea[i] = group[i] == biggest;
    }
  }


  // ---------- 좌표 <-> 칸 번호 ----------

  // 위도 -> 행 번호
  public int row(double lat) {
    return (int) Math.floor((lat - LAT0) / CELL);
  }

  // 경도 -> 열 번호
  public int col(double lng) {
    return (int) Math.floor((lng - LON0) / CELL);
  }

  // 칸 번호 -> 칸 한가운데의 {위도, 경도}
  public double[] center(int row, int col) {
    return new double[]{
        LAT0 + (row + 0.5) * CELL,
        LON0 + (col + 0.5) * CELL
    };
  }


  // ---------- 바다인지 확인 ----------

  // 칸 번호가 격자 범위 안에 있는가
  public boolean inRange(int row, int col) {
    return row >= 0 && row < ROWS && col >= 0 && col < COLS;
  }

  // 그 칸의 바다 비율 (0.0 = 전부 육지, 1.0 = 전부 바다)
  public double seaFraction(int row, int col) {
    if (!inRange(row, col)) {
      return 0.0;
    }
    return seaFraction(row * COLS + col);
  }

  // 배열 위치로 바로 꺼내는 용도 (내부에서만 씀)
  //
  // byte 는 -128~127 범위라서, 128 이상인 값은 음수로 읽힌다.
  // & 0xFF 를 붙이면 0~255 로 제대로 바뀐다.
  private double seaFraction(int index) {
    return (grid[index] & 0xFF) / 255.0;
  }

  // 그 칸으로 배가 지나갈 수 있는가
  public boolean navigable(int row, int col) {
    return seaFraction(row, col) >= MIN_SEA;
  }

  // 좌표로 바로 확인 (A* 가 아니라 화면·검증에서 쓰기 편하게)
  public boolean navigableAt(double lat, double lng) {
    return navigable(row(lat), col(lng));
  }


  // ---------- 항구를 바다 칸으로 옮기기 ----------

  // 그 칸이 큰 바다 덩어리에 이어져 있는가.
  // 좁은 만 안쪽처럼 바깥 바다와 끊어진 칸은 false (거기서는 아무 데도 갈 수 없다).
  public boolean inMainSea(int row, int col) {
    return inRange(row, col) && mainSea[row * COLS + col];
  }


  // 항구는 땅에 붙어 있어서 그 칸이 '통과 불가'로 나오는 경우가 많다.
  // (예: 부산항이 있는 칸은 바다 비율이 7% 뿐)
  // 그래서 항구 근처에서 가장 가까운 칸을 찾아 경로의 시작/끝으로 삼는다.
  //
  // 이때 '큰 바다에 이어진 칸'만 고른다. 그냥 통과 가능한 칸을 고르면,
  // 마산항처럼 2칸짜리로 끊긴 만 안쪽이 잡혀서 경로를 아예 못 찾게 된다.
  // (마산항은 큰 바다까지 7.1km — 아래 탐색 범위 15km 안에 들어온다)
  //
  // 돌려주는 것: {행, 열}. 근처에 이어진 바다가 없으면 null
  public int[] nearestNavigable(double lat, double lng) {
    int r0 = row(lat);
    int c0 = col(lng);

    // 그 칸이 이미 쓸 수 있으면 그대로 쓴다.
    if (inMainSea(r0, c0)) {
      return new int[]{r0, c0};
    }

    // 아니면 주변을 훑어 가장 가까운 칸을 찾는다.
    int[] best = null;
    double bestDistance = Double.MAX_VALUE;

    for (int r = r0 - SEARCH_RANGE; r <= r0 + SEARCH_RANGE; r++) {
      for (int c = c0 - SEARCH_RANGE; c <= c0 + SEARCH_RANGE; c++) {
        if (!inMainSea(r, c)) {
          continue;
        }
        // 칸 가운데까지의 거리를 재서 가장 가까운 칸을 고른다.
        double[] mid = center(r, c);
        double distance = Math.hypot(mid[0] - lat, mid[1] - lng);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = new int[]{r, c};
        }
      }
    }
    return best;
  }
}
