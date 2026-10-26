package com.green.Navigate.traffic.dto;

import lombok.Data;

import java.util.List;


// 실시간 해상교통 스냅샷 (MTIS #244, 국내 해역 격자만)
@Data
public class RealtimeDTO {
  private String regDt;           // MTIS 자료 기준 시각
  private String fetchedAt;       // 서버가 받은 시각
  private Integer totalShips;     // 선박 수 합계 (척)
  private Integer shipCells;      // 선박이 있는 칸 수
  private GridDTO top;            // 가장 붐비는 칸
  private List<GridDTO> grids;    // 칸 목록 (지도에 그림)
  private String error;           // 마지막 수집 실패 사유 (성공하면 null)
}
