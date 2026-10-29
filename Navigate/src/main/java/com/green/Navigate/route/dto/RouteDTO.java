package com.green.Navigate.route.dto;

import com.green.Navigate.main.dto.PortDTO;
import lombok.Data;


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
  // 1단계에서는 최소경로와 똑같은 직선을 넣어 둔다 (4단계에서 진짜 계산으로 바꿀 예정)
  private RoutePathDTO safe;

  // 일이 잘못됐을 때 사유 (예: "항구를 찾을 수 없습니다")
  // 성공하면 null 이 들어간다. 화면에서는 이 값이 있으면 메시지를 보여 준다.
  private String error;
}
