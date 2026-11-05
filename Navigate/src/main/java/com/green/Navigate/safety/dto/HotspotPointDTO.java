package com.green.Navigate.safety.dto;

import lombok.Data;


// 좌초·좌주 다발지점 한 곳 (MTIS #231, 최근 5년 좌초 사고를 묶은 위치)
@Data
public class HotspotPointDTO {
  private Double lat;       // 위도
  private Double lng;       // 경도
  private Integer size;     // 묶인 사고 수
  private String near;      // 가장 가까운 무역항 기준 위치
}
