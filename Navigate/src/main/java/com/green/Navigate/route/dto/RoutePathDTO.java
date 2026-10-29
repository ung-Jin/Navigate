package com.green.Navigate.route.dto;

import lombok.Data;

import java.util.List;


// 경로 하나 (최소경로 또는 안전경로)
// 지도에 선을 그리는 데 필요한 좌표 목록과, 화면에 보여줄 숫자들을 담는다.
@Data
public class RoutePathDTO {

  // 경로가 지나는 점들. [[위도, 경도], [위도, 경도], ...] 모양
  // 지금(1단계)은 출발점과 도착점 2개뿐 -> 지도에 직선 하나가 그려짐
  // 나중에 A* 를 넣으면 중간 점들이 많이 늘어나서 육지를 피해 꺾이는 선이 된다.
  private List<double[]> coords;

  // 경로 전체 길이 (킬로미터)
  private double km;

  // 경로 전체 길이 (해리, nautical mile). 배에서는 보통 해리를 쓴다. 1해리 = 1.852km
  private double nm;

  // 예상 소요 시간 (시간 단위). 거리 / 속력 으로 계산
  private double hours;
}
