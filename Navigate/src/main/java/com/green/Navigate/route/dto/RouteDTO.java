package com.green.Navigate.route.dto;

import com.green.Navigate.main.dto.PortDTO;
import lombok.Data;

import java.util.List;


// 경로찾기 응답 전체
// 브라우저(route.js)가 이 모양 그대로 JSON 으로 받아서 지도에 그린다.
@Data
public class RouteDTO {

  // 출발 항구 (이름 + 위도 + 경도)
  private PortDTO from;

  // 도착 항구
  private PortDTO to;

  // 최소경로 — 날씨·교통·안전 신경 쓰지 않고 거리만 짧게
  private RoutePathDTO shortest;

  // 안전경로 — 날씨·교통·안전 데이터를 반영해서 위험한 곳을 피해 가는 경로
  private RoutePathDTO safe;

  // 두 경로가 같은가. 피할 위험이 없으면 돌아갈 이유도 없어서 같아진다.
  private boolean same;

  // 최소경로 대비 안전경로가 얼마나 늘었는지 / 줄었는지 (%)
  private Double kmPct;         // 거리 (+ 일수록 더 돌아감)
  private Double exposurePct;   // 위험 노출 (- 일수록 더 안전해짐)

  // 어떤 자료로 위험도를 매겼는지 (화면에 표시)
  private List<String> sources;

  // 위험을 얼마나 피했는지 (0 이면 거리만, 클수록 많이 돌아감)
  private double alpha;

  // 일이 잘못됐을 때 사유 (예: "항구를 찾을 수 없습니다")
  // 성공하면 null 이 들어간다. 화면에서는 이 값이 있으면 메시지를 보여 준다.
  private String error;
}
