package com.green.Navigate.safety.dto;

import lombok.Data;

import java.util.List;


// 사고 다발 자료 한 해 — 사고다발 격자(#243) + 좌초 다발지점(#231)
@Data
public class HotspotDTO {
  private Integer year;                    // 자료 연도 (MTIS 가 매년 갱신 — 가장 최근 연도)
  private Integer gridTotal;               // 사고가 한 건이라도 있는 격자 수 (다발 아닌 칸 포함)
  private List<AccidentGridDTO> grids;     // 사고다발 격자 (MTIS 가 다발로 지정한 칸만)
  private List<HotspotPointDTO> stranding; // 좌초·좌주 다발지점
  private String error;                    // 받지 못한 사유 (성공하면 null)
}
