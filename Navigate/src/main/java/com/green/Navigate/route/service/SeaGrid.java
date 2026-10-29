package com.green.Navigate.route.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;


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

  // 한 칸의 크기 (도). MTIS 해양격자와 같다.
  public static final double CELL = 0.025;

  // 격자가 덮는 범위의 남서쪽 끝 (0행 0열의 위치)
  public static final double LAT0 = 32.0;
  public static final double LON0 = 124.0;

  // 격자 크기
  public static final int ROWS = 280;   // 위도 32.0 ~ 39.0
  public static final int COLS = 320;   // 경도 124.0 ~ 132.0

  // 배가 지나갈 수 있다고 볼 최소 바다 비율
  // 0.3 = 칸의 30% 이상이 바다여야 통과 가능
  //
  // 왜 0 보다 크게 잡는가: 칸이 2.5km 라서 "귀퉁이만 바다"인 칸이 많다.
  // 그런 칸을 지나가게 두면 경로가 육지를 스치듯 지나가 버린다.
  // 특히 화물선·유조선은 큰 배라 연안에 바짝 붙지 않으므로 넉넉히 잡는다.
  private static final double MIN_SEA = 0.3;

  // 항구에서 가장 가까운 바다 칸을 찾을 때 몇 칸까지 둘러볼지 (6칸 = 약 15km)
  private static final int SEARCH_RANGE = 6;


  // 격자 자료. 칸 하나당 1바이트. grid[행 * COLS + 열] 로 꺼낸다.
  private byte[] grid;


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
    log.info("바다 격자 준비 완료: {}x{} 칸 중 통과 가능 {}칸", ROWS, COLS, navigable);
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

  // 항구는 땅에 붙어 있어서 그 칸이 '통과 불가'로 나오는 경우가 많다.
  // (예: 부산항이 있는 칸은 바다 비율이 7% 뿐)
  // 그래서 항구 근처에서 가장 가까운 통과 가능한 칸을 찾아 경로의 시작/끝으로 삼는다.
  //
  // 돌려주는 것: {행, 열}. 근처에 바다가 없으면 null
  public int[] nearestNavigable(double lat, double lng) {
    int r0 = row(lat);
    int c0 = col(lng);

    // 그 칸이 이미 통과 가능하면 그대로 쓴다.
    if (navigable(r0, c0)) {
      return new int[]{r0, c0};
    }

    // 아니면 주변을 한 겹씩 넓혀 가며 찾는다. (가까운 칸부터)
    int[] best = null;
    double bestDistance = Double.MAX_VALUE;

    for (int r = r0 - SEARCH_RANGE; r <= r0 + SEARCH_RANGE; r++) {
      for (int c = c0 - SEARCH_RANGE; c <= c0 + SEARCH_RANGE; c++) {
        if (!navigable(r, c)) {
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
