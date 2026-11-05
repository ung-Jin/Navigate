package com.green.Navigate.safety.controller;

import com.green.Navigate.safety.dto.HotspotDTO;
import com.green.Navigate.safety.dto.RiskForecastDTO;
import com.green.Navigate.safety.service.HotspotService;
import com.green.Navigate.safety.service.RiskForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequiredArgsConstructor
public class SafetyApiController {

  private final RiskForecastService riskForecastService;
  private final HotspotService hotspotService;

  // 안전진단: 해양사고 위험 예보 (date = yyyyMMdd 오늘~모레, time = 00·03·…·21)
  // 같은 날 같은 시각은 저장해 둔 것을 돌려줌 (MTIS 는 처음 한 번만 부름)
  @GetMapping("/api/safety/risk")
  public RiskForecastDTO risk(@RequestParam String date, @RequestParam String time) {
    return riskForecastService.get(date, time);
  }

  // 사고 다발: 사고다발 격자(#243) + 좌초 다발지점(#231) — 가장 최근 연도 (한 번 받으면 파일에 두고 계속 씀)
  @GetMapping("/api/safety/hotspots")
  public HotspotDTO hotspots() {
    return hotspotService.getLatest();
  }
}
