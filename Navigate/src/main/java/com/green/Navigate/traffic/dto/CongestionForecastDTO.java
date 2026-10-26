package com.green.Navigate.traffic.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;


// 해상교통 혼잡 예보 (MTIS #259) — 한 예보 시각. 선박 수는 없고 혼잡 지수(%)·단계만 있음
@Data
public class CongestionForecastDTO {
  private String fcstDate;              // 예보 날짜 (yyyyMMdd)
  private String fcstTime;              // 예보 시각 (HH00, 1시간 단위)
  private String created;               // KOMSA 예보 생성 시각
  private String fetchedDay;            // 서버가 받은 날
  private Map<String, Integer> counts;  // 단계별 칸 수 (낮음·보통·높음·매우높음)
  private CongestionGridDTO top;        // 혼잡 지수가 가장 높은 칸
  private List<CongestionGridDTO> grids; // 국내 해역 칸 전체
  private String error;                 // 받지 못한 사유 (성공하면 null)
}
