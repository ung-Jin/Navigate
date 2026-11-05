package com.green.Navigate.safety.dto;

import lombok.Data;


// 사고다발 격자 한 칸 (MTIS #243, 해양격자 약 2.8 × 2.3km)
@Data
public class AccidentGridDTO {
  private String id;        // 해양격자 번호 (GR4_…)
  private Double lat;       // 칸 가운데 위도
  private Double lng;       // 칸 가운데 경도
  private Integer count;    // 사고 발생 척수
  private Double avg;       // 연평균 사고 척수
  private String near;      // 가장 가까운 무역항 기준 위치
}
