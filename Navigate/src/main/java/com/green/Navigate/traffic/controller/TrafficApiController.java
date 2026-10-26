package com.green.Navigate.traffic.controller;

import com.green.Navigate.traffic.dto.CongestionForecastDTO;
import com.green.Navigate.traffic.dto.RealtimeDTO;
import com.green.Navigate.traffic.service.CongestionForecastService;
import com.green.Navigate.traffic.service.TrafficService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequiredArgsConstructor
public class TrafficApiController {

  private final TrafficService trafficService;
  private final CongestionForecastService congestionForecastService;

  // 교통현황(지금): 실시간 선박 격자 + 요약 (서버가 10분마다 받아 둔 것, 이 요청은 MTIS 를 부르지 않음)
  @GetMapping("/api/traffic/realtime")
  public RealtimeDTO realtime() {
    return trafficService.getRealtime();
  }

  // 교통현황(미래): 혼잡 예보 (date = yyyyMMdd 오늘~모레, time = 00~23)
  // 같은 날 같은 시각은 저장해 둔 것을 돌려줌 (MTIS 는 처음 한 번만 부름)
  @GetMapping("/api/traffic/forecast")
  public CongestionForecastDTO forecast(@RequestParam String date, @RequestParam String time) {
    return congestionForecastService.get(date, time);
  }
}
