package com.green.Navigate.safety.dto;

import lombok.Data;


// 사고위험 예보 한 칸 (약 4.2 × 4.1km)
@Data
public class RiskGridDTO {
  private String id;        // 예보 격자 번호
  private Double lat;       // 칸 가운데 위도
  private Double lng;       // 칸 가운데 경도
  private String level;     // 위험 등급: 낮음 / 보통 / 주의 / 경계
  private Double index;     // 위험 지수 (클수록 위험)
  private Double wave;      // 예보 파고 (m)
  private Double wind;      // 예보 풍속 (m/s)
  private Double windDir;   // 예보 풍향 (°)
  private String near;      // 가장 가까운 무역항 기준 위치
}
