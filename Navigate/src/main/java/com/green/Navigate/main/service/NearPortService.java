package com.green.Navigate.main.service;

import com.green.Navigate.main.dto.PortDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

// 좌표 → "가장 가까운 무역항 기준 위치" 글자 (예: 부산항에서 3km, 3km 안이면 "부산항 부근")
// 교통현황·안전진단 지도 설명에 공통으로 씀. 무역항 목록은 DB ports 테이블에서 처음 한 번만 읽음
@Slf4j
@Service
@RequiredArgsConstructor
public class NearPortService {

  private final MainService mainService;
  private volatile List<PortDTO> ports;

  public String describe(double lat, double lng) {
    PortDTO best = null;
    double bestKm = Double.MAX_VALUE;
    for (PortDTO p : ports()) {
      double km = Math.hypot((lat - p.getLat()) * 111, (lng - p.getLng()) * 111 * Math.cos(Math.toRadians(lat)));
      if (km < bestKm) {
        bestKm = km;
        best = p;
      }
    }
    if (best == null) {
      return null;
    }
    return bestKm < 3 ? best.getName() + " 부근" : best.getName() + "에서 " + Math.round(bestKm) + "km";
  }

  private List<PortDTO> ports() {
    if (ports == null) {
      try {
        ports = mainService.getPortList();
      } catch (RuntimeException e) {
        log.warn("무역항 목록을 읽지 못했습니다 (DB): {}", e.getMessage());
        return List.of();  // DB 가 안 되면 이번엔 이름 없이, 다음 호출 때 다시 시도
      }
    }
    return ports;
  }
}
