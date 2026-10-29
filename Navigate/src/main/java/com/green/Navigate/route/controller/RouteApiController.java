package com.green.Navigate.route.controller;

import com.green.Navigate.route.dto.RouteDTO;
import com.green.Navigate.route.service.RouteService;
import com.green.Navigate.route.service.SeaGrid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


// 경로찾기 API
// 브라우저가 이 주소를 부르면 RouteService 가 계산한 결과를 JSON 으로 돌려준다.
@RestController
@RequiredArgsConstructor
public class RouteApiController {

  private final RouteService routeService;
  private final SeaGrid seaGrid;

  // 경로찾기: 출발 항구 이름 -> 도착 항구 이름
  //
  // 호출 예: /api/route?from=부산항&to=울산항
  //
  // 돌려주는 것: 최소경로 + 안전경로 (지금은 둘 다 직선)
  @GetMapping("/api/route")
  public RouteDTO route(@RequestParam String from, @RequestParam String to) {
    return routeService.findRoute(from, to);
  }


  // ---------- 아래는 개발 중 확인용 (완성되면 지워도 됨) ----------

  // 바다 격자를 지도에 그려 보기 위한 자료
  // 호출 예: /api/route/grid?minLat=34&maxLat=36&minLng=128&maxLng=130
  //
  // 화면에서 격자를 켜면 바다 칸이 파랗게 칠해진다. 해안선과 맞는지 눈으로 확인하는 용도.
  // 화면 전체를 한 번에 주면 칸이 너무 많아지므로, 지금 보고 있는 범위만 달라고 한다.
  @GetMapping("/api/route/grid")
  public Map<String, Object> grid(@RequestParam double minLat, @RequestParam double maxLat,
                                  @RequestParam double minLng, @RequestParam double maxLng) {

    List<Map<String, Object>> cells = new ArrayList<>();

    int rowFrom = seaGrid.row(minLat);
    int rowTo = seaGrid.row(maxLat);
    int colFrom = seaGrid.col(minLng);
    int colTo = seaGrid.col(maxLng);

    for (int r = rowFrom; r <= rowTo; r++) {
      for (int c = colFrom; c <= colTo; c++) {
        double fraction = seaGrid.seaFraction(r, c);
        if (fraction <= 0) {
          continue;      // 육지뿐인 칸은 보낼 필요 없음 (양이 줄어 화면이 빨라짐)
        }
        double[] mid = seaGrid.center(r, c);

        Map<String, Object> cell = new HashMap<>();
        cell.put("lat", mid[0]);
        cell.put("lng", mid[1]);
        cell.put("sea", Math.round(fraction * 100) / 100.0);   // 바다 비율
        cell.put("ok", seaGrid.navigable(r, c));               // 통과 가능 여부
        cells.add(cell);
      }
    }

    Map<String, Object> result = new HashMap<>();
    result.put("cell", SeaGrid.CELL);   // 칸 크기 (화면에서 네모를 그릴 때 필요)
    result.put("cells", cells);
    return result;
  }


  // 좌표 하나가 바다인지 확인
  // 호출 예: /api/route/sea?lat=35.1&lng=129.033
  @GetMapping("/api/route/sea")
  public Map<String, Object> sea(@RequestParam double lat, @RequestParam double lng) {
    Map<String, Object> result = new HashMap<>();
    result.put("seaFraction", seaGrid.seaFraction(seaGrid.row(lat), seaGrid.col(lng)));
    result.put("navigable", seaGrid.navigableAt(lat, lng));

    // 통과 불가라면 가장 가까운 바다 칸이 어디인지도 알려 준다.
    int[] near = seaGrid.nearestNavigable(lat, lng);
    if (near != null) {
      double[] mid = seaGrid.center(near[0], near[1]);
      result.put("nearestLat", mid[0]);
      result.put("nearestLng", mid[1]);
    }
    return result;
  }
}
