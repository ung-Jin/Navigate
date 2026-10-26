package com.green.Navigate.traffic.dto;

import lombok.Data;


// 해상교통 혼잡 예보 한 칸 (실시간 교통과 같은 격자, 약 2.8 × 2.3km)
@Data
public class CongestionGridDTO {
  private String id;      // MTIS 격자 ID (실시간 교통 grid_id 와 같은 형식)
  private Double lat;     // 칸 가운데 위도
  private Double lng;     // 칸 가운데 경도
  private Double index;   // 혼잡 지수 (%) — 실시간 밀집도와 같은 개념 (선박이 차지하는 유효 면적 비율)
  private String level;   // 혼잡 단계: 낮음 / 보통 / 높음 / 매우높음
  private Double wind;    // 예보 풍속 (m/s)
  private Double wave;    // 예보 파고 (m)
  private String near;    // 가장 가까운 무역항 기준 위치
}
