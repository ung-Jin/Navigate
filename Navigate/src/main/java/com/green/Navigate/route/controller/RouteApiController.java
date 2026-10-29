package com.green.Navigate.route.controller;

import com.green.Navigate.route.dto.RouteDTO;
import com.green.Navigate.route.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


// 경로찾기 API
// 브라우저가 이 주소를 부르면 RouteService 가 계산한 결과를 JSON 으로 돌려준다.
@RestController
@RequiredArgsConstructor
public class RouteApiController {

  private final RouteService routeService;

  // 경로찾기: 출발 항구 이름 -> 도착 항구 이름
  //
  // 호출 예: /api/route?from=부산항&to=울산항
  //
  // 돌려주는 것: 최소경로 + 안전경로 (지금은 둘 다 직선)
  @GetMapping("/api/route")
  public RouteDTO route(@RequestParam String from, @RequestParam String to) {
    return routeService.findRoute(from, to);
  }
}
