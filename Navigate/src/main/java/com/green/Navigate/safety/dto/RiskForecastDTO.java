package com.green.Navigate.safety.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;


// 해양사고 위험 예보 (MTIS #261) — 한 예보 시각
@Data
public class RiskForecastDTO {
  private String fcstDate;              // 예보 날짜 (yyyyMMdd)
  private String fcstTime;              // 예보 시각 (HH00, 3시간 단위)
  private String created;               // KOMSA 예보 생성 시각
  private String fetchedDay;            // 서버가 받은 날 (예보는 매일 새로 나오므로 오늘 받은 것만 씀)
  private Map<String, Integer> counts;  // 등급별 칸 수 (낮음·보통·주의·경계)
  private Double maxWave;               // 전체 칸 중 최대 예보 파고 (m)
  private Double maxWind;               // 전체 칸 중 최대 예보 풍속 (m/s)
  private RiskGridDTO top;              // 위험 지수가 가장 높은 칸
  private List<RiskGridDTO> grids;      // 지도에 그릴 칸 ('낮음' 제외)
  private String error;                 // 받지 못한 사유 (성공하면 null)
}
