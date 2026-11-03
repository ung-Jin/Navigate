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

  // 위험 노출량 = Σ(위험도 × km)
  // 위험한 곳을 오래 지날수록 커진다. 두 경로를 견주는 핵심 지표.
  private double exposure;

  // 위험도가 높은(0.6 이상) 구간의 길이 (km)
  private double highKm;

  // 경로에서 만나는 가장 높은 위험도 (0~1)
  private double maxRisk;
}
