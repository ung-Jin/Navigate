package com.green.Navigate.traffic.dto;

import lombok.Data;


// 실시간 교통 격자 한 칸 (약 2.8 × 2.3km)
@Data
public class GridDTO {
  private String id;      // MTIS 격자 ID (예: GR4_F2O41_P1)
  private Double lat;     // 칸 가운데 위도
  private Double lng;     // 칸 가운데 경도
  private Integer vmtc;   // 선박 수 (척)
  private Double dnsty;   // 밀집도 (%)
  private String near;    // 가장 가까운 무역항 기준 위치 (예: 부산항에서 3km)
}
