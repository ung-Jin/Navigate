package com.green.Navigate.route.service;

import com.green.Navigate.route.util.GeoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;


// A* 최소경로 — 바다 칸만 밟아서 출발 칸에서 도착 칸까지 가장 짧은 길을 찾는다.
//
// A* 가 하는 일을 한 줄로 쓰면:
//   "출발점에서 여기까지 온 거리" + "여기서 목적지까지 남은 직선거리" 가 가장 작은 칸부터 살펴본다.
//
// 뒷부분(남은 직선거리)을 빼면 다익스트라 알고리즘이 된다.
// 그 한 항목 덕분에 사방으로 퍼지지 않고 목적지 쪽을 먼저 파고들어서 훨씬 빠르다.
//
// 여기서는 거리만 따진다. 날씨·교통·사고위험을 반영한 안전경로는 4단계에서 붙인다.
@Slf4j
@Component
@RequiredArgsConstructor
public class AStar {

  private final SeaGrid seaGrid;

  // 이웃 8방향 {행 변화, 열 변화}
  private static final int[][] DIRECTIONS = {
      {-1, 0}, {1, 0}, {0, -1}, {0, 1},        // 상 하 좌 우
      {-1, -1}, {-1, 1}, {1, -1}, {1, 1}       // 대각선 4개
  };

  // 우선순위 큐에 넣을 항목. priority 가 작을수록 먼저 꺼낸다.
  private record Node(int cell, double priority) {
  }


  // 항구 둘레 이 거리 안에서는 위험 가중치를 점차 줄인다.
  //
  // 항만 근처는 배가 몰리고 사고 이력도 많아 위험도가 높게 나오는데, 항구는 반드시 드나들어야 한다.
  // 거기에 회피 비용을 그대로 매기면 A* 가 항만 주변을 빙 돌아 진입하려 해서 경로가 이상해진다.
  // 접안·진입은 도선사가 안내하는 구간이라 애초에 경로 예측 대상이 아니기도 하다.
  private static final double PORT_EASE_KM = 5.0;


  // 거리만 보는 최소경로 (위험도 무시)
  public List<double[]> find(int[] start, int[] goal) {
    return find(start, goal, null, 0.0);
  }


  // 출발 칸 {행, 열} 에서 도착 칸 {행, 열} 까지 바닷길을 찾는다.
  //
  // risk  : 칸별 위험도 (null 이면 거리만 봄)
  // alpha : 위험을 얼마나 피할지. 0 이면 최소경로, 클수록 많이 돌아간다
  //
  // 간선 비용 = 거리 × (1 + alpha × 위험도)
  //   alpha = 0  -> 비용이 곧 거리     = 최소경로
  //   alpha = 3  -> 위험한 칸이 4배까지 비싸짐 = 안전경로
  //
  // 돌려주는 것: 지나가는 지점들의 {위도, 경도} 목록. 길이 없으면 null
  public List<double[]> find(int[] start, int[] goal, RiskMap.Snapshot risk, double alpha) {

    int startCell = toIndex(start[0], start[1]);
    int goalCell = toIndex(goal[0], goal[1]);

    // 출발과 도착이 같은 칸이면 그 칸 하나만 돌려준다.
    if (startCell == goalCell) {
      List<double[]> one = new ArrayList<>();
      one.add(seaGrid.center(start[0], start[1]));
      return one;
    }

    double[] startCenter = seaGrid.center(start[0], start[1]);
    double[] goalCenter = seaGrid.center(goal[0], goal[1]);

    Map<Integer, Double> costSoFar = new HashMap<>();   // 칸 -> 출발점에서 여기까지 온 거리(km)
    Map<Integer, Integer> cameFrom = new HashMap<>();   // 칸 -> 바로 직전에 있던 칸 (경로 복원용)
    Set<Integer> done = new HashSet<>();                // 이미 확정된 칸 (다시 볼 필요 없음)

    PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::priority));
    costSoFar.put(startCell, 0.0);
    queue.add(new Node(startCell, 0.0));

    while (!queue.isEmpty()) {

      // 우선순위가 가장 낮은(= 가장 유망한) 칸을 꺼낸다.
      int cell = queue.poll().cell();

      // 이미 확정된 칸이면 건너뛴다.
      // (같은 칸이 더 나쁜 값으로 큐에 여러 번 들어가 있을 수 있어서 필요하다)
      if (done.contains(cell)) {
        continue;
      }

      // 도착했으면 여기서 끝낸다.
      if (cell == goalCell) {
        return buildPath(cameFrom, startCell, goalCell);
      }

      done.add(cell);

      int row = rowOf(cell);
      int col = colOf(cell);
      double[] here = seaGrid.center(row, col);
      double costHere = costSoFar.get(cell);

      // 이웃 8칸을 살펴본다.
      for (int[] d : DIRECTIONS) {
        int nextRow = row + d[0];
        int nextCol = col + d[1];

        // 바다가 아니면 못 간다.
        if (!seaGrid.navigable(nextRow, nextCol)) {
          continue;
        }

        // 대각선으로 갈 때는 양옆 두 칸도 바다여야 한다.
        //
        //   ┌────┬────┐   양옆이 육지인데 대각선으로 가면
        //   │바다 │육지 │   두 육지 사이를 비스듬히 뚫고 지나가 버린다.
        //   ├────┼────┤   실제 배는 그렇게 못 지나간다.
        //   │육지 │바다 │
        //   └────┴────┘
        if (d[0] != 0 && d[1] != 0) {
          if (!seaGrid.navigable(nextRow, col) || !seaGrid.navigable(row, nextCol)) {
            continue;
          }
        }

        int nextCell = toIndex(nextRow, nextCol);
        if (done.contains(nextCell)) {
          continue;
        }

        // 여기까지 오는 데 드는 비용 = 지금까지 온 비용 + 두 칸 가운데 사이 거리 × 위험 할증
        double[] next = seaGrid.center(nextRow, nextCol);
        double step = GeoUtil.distanceKm(here, next);

        if (risk != null && alpha > 0) {
          // 항구 가까이에서는 alpha 를 줄여, 어차피 지나야 하는 구간을 돌아가지 않게 한다
          double ease = portEase(next, startCenter, goalCenter);
          step *= 1 + alpha * ease * risk.at(nextRow, nextCol);
        }
        double newCost = costHere + step;

        // 더 짧은 길을 이미 찾아 뒀으면 넘어간다.
        Double known = costSoFar.get(nextCell);
        if (known != null && known <= newCost) {
          continue;
        }

        costSoFar.put(nextCell, newCost);
        cameFrom.put(nextCell, cell);

        // A* 의 핵심 — 남은 직선거리를 더해서 우선순위를 매긴다.
        //
        // 직선거리는 실제로 가야 할 거리보다 절대 길 수 없다(돌아가면 더 멀어지니까).
        // 이렇게 "실제보다 작게 어림잡는" 값을 써야 최단경로가 보장된다.
        double remaining = GeoUtil.distanceKm(next, goalCenter);
        queue.add(new Node(nextCell, newCost + remaining));
      }
    }

    // 큐가 비었는데 도착 못 했다 = 두 칸이 서로 다른 바다 덩어리에 있다
    return null;
  }


  // 출발·도착 항구에 가까울수록 위험 가중치를 줄이는 비율 (0 = 완전히 무시, 1 = 그대로 적용)
  //
  //   항구 ●━━━━━━━━━━━━━▶
  //        0km    5km      먼바다
  //        0.0 ─── 1.0 ─── 1.0
  private double portEase(double[] point, double[] startCenter, double[] goalCenter) {
    double km = Math.min(GeoUtil.distanceKm(point, startCenter),
                         GeoUtil.distanceKm(point, goalCenter));
    return Math.min(1.0, km / PORT_EASE_KM);
  }


  // cameFrom 을 거꾸로 따라가며 경로를 복원하고, 계단 모양을 직선으로 편다.
  private List<double[]> buildPath(Map<Integer, Integer> cameFrom, int startCell, int goalCell) {

    // 도착 칸에서 시작해 "직전 칸"을 계속 따라가면 출발 칸에 닿는다.
    List<Integer> cells = new ArrayList<>();
    int cell = goalCell;
    cells.add(cell);
    while (cell != startCell) {
      cell = cameFrom.get(cell);
      cells.add(cell);
    }
    Collections.reverse(cells);   // 거꾸로 담았으니 뒤집는다

    // 칸 번호를 칸 가운데 좌표로 바꾼다.
    List<double[]> points = new ArrayList<>();
    for (int c : cells) {
      points.add(seaGrid.center(rowOf(c), colOf(c)));
    }

    return simplify(points);
  }


  // 계단 모양 경로를 직선으로 편다.
  //
  // 격자 위를 움직이면 45°나 90°로만 꺾여서 경로가 계단처럼 된다.
  //
  //   편치 않은 경로      편 경로
  //      ┌─┐              ╱
  //    ┌─┘ └┐           ╱
  //    │    └─┐        ╱
  //
  // 방법: 맨 끝에서부터 하나씩 당겨 오며, 직선으로 이을 수 있는 "가장 먼" 지점을 찾는다.
  // 꺾임이 줄어 보기 좋아질 뿐 아니라, 계단보다 직선이 짧으므로 실제 거리도 줄어든다.
  //
  // 앞에서부터 한 칸씩 늘려 가는 방법도 있고 그 편이 빠르지만, 눈앞에서 막히면 거기서 끊어 버려
  // 더 멀리 이을 수 있는 기회를 놓친다. 실제로 울산항→제주항에서 13km 차이가 났다.
  // 뒤에서부터 찾으면 비교 횟수는 늘지만, 막히는 직선은 육지를 만나는 즉시 멈추므로 실제로는 빠르다.
  private List<double[]> simplify(List<double[]> points) {

    if (points.size() <= 2) {
      return points;
    }

    List<double[]> out = new ArrayList<>();
    out.add(points.get(0));

    int i = 0;
    while (i < points.size() - 1) {

      // 맨 끝에서부터 당겨 오며, 바다만 지나는 직선으로 이을 수 있는 가장 먼 지점을 찾는다.
      int j = points.size() - 1;
      while (j > i + 1 && !lineIsSea(points.get(i), points.get(j))) {
        j--;
      }

      out.add(points.get(j));
      i = j;
    }

    return out;
  }


  // 두 지점을 잇는 직선이 바다 칸만 지나는가
  //
  // 직선 위를 일정 간격으로 짚어 보는 방법도 있지만, 그러면 칸 모서리를 살짝 스치고 지나가는
  // 짧은 구간을 놓친다 (실제로 0.3km 짜리 육지 통과를 놓치는 일이 있었다).
  // 그래서 직선이 실제로 지나는 칸을 하나씩 빠짐없이 따라간다.
  //
  // 방법: 직선을 따라가다 보면 칸의 세로선 또는 가로선을 번갈아 넘는다.
  //       "다음 세로선까지" 와 "다음 가로선까지" 중 가까운 쪽을 먼저 넘으면서 칸을 옮긴다.
  private boolean lineIsSea(double[] a, double[] b) {

    // 격자 좌표(실수)로 바꾼다. x = 열 방향(경도), y = 행 방향(위도)
    double x0 = (a[1] - SeaGrid.LON0) / SeaGrid.CELL;
    double y0 = (a[0] - SeaGrid.LAT0) / SeaGrid.CELL;
    double x1 = (b[1] - SeaGrid.LON0) / SeaGrid.CELL;
    double y1 = (b[0] - SeaGrid.LAT0) / SeaGrid.CELL;

    int col = (int) Math.floor(x0);
    int row = (int) Math.floor(y0);
    int lastCol = (int) Math.floor(x1);
    int lastRow = (int) Math.floor(y1);

    if (!seaGrid.navigable(row, col)) {
      return false;
    }

    double dx = x1 - x0;
    double dy = y1 - y0;
    int stepCol = dx > 0 ? 1 : -1;
    int stepRow = dy > 0 ? 1 : -1;

    // 다음 경계선까지 가는 데 걸리는 비율 (전체 길이를 1로 봤을 때)
    double toNextCol = dx == 0 ? Double.MAX_VALUE
        : (dx > 0 ? col + 1 - x0 : x0 - col) / Math.abs(dx);
    double toNextRow = dy == 0 ? Double.MAX_VALUE
        : (dy > 0 ? row + 1 - y0 : y0 - row) / Math.abs(dy);

    // 한 칸을 완전히 건너는 데 걸리는 비율
    double colStride = dx == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx);
    double rowStride = dy == 0 ? Double.MAX_VALUE : 1 / Math.abs(dy);

    // 격자를 벗어나는 일이 없도록 한도를 둔다 (ROWS + COLS 면 어떤 직선이든 충분하다)
    int limit = SeaGrid.ROWS + SeaGrid.COLS + 2;

    while ((row != lastRow || col != lastCol) && limit-- > 0) {

      if (Math.abs(toNextCol - toNextRow) < 1e-9) {
        // 세로선과 가로선을 동시에 넘는다 = 네 칸이 만나는 모서리를 정확히 지난다.
        // 대각선으로 넘는 셈이므로 A* 와 같은 규칙을 적용한다 (양옆 두 칸도 바다여야 함).
        if (!seaGrid.navigable(row + stepRow, col) || !seaGrid.navigable(row, col + stepCol)) {
          return false;
        }
        col += stepCol;
        row += stepRow;
        toNextCol += colStride;
        toNextRow += rowStride;

      } else if (toNextCol < toNextRow) {
        col += stepCol;
        toNextCol += colStride;

      } else {
        row += stepRow;
        toNextRow += rowStride;
      }

      if (!seaGrid.navigable(row, col)) {
        return false;
      }
    }
    return true;
  }


  // ---------- 칸 번호 다루기 ----------
  //
  // (행, 열) 두 숫자를 HashMap 의 키로 쓰려면 번거로우므로 하나의 정수로 합친다.
  // 격자가 280 x 320 이라 "행 x 320 + 열" 로 합치면 겹치지 않는다.

  private int toIndex(int row, int col) {
    return row * SeaGrid.COLS + col;
  }

  private int rowOf(int index) {
    return index / SeaGrid.COLS;
  }

  private int colOf(int index) {
    return index % SeaGrid.COLS;
  }
}
