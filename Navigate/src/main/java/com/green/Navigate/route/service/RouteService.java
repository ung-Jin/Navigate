package com.green.Navigate.route.service;

import com.green.Navigate.main.dto.PortDTO;
import com.green.Navigate.main.service.MainService;
import com.green.Navigate.route.dto.RouteDTO;
import com.green.Navigate.route.dto.RoutePathDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


// 경로 계산 담당
//
// [1단계 - 지금] 출발 항구와 도착 항구를 잇는 "직선"을 돌려준다.
//                육지를 뚫고 지나가지만, 화면 연결이 제대로 되는지 먼저 확인하기 위한 것.
// [2단계 예정] 바다/육지 격자를 만든다.
// [3단계 예정] A* 알고리즘으로 육지를 피하는 최소경로를 찾는다.
// [4단계 예정] 교통·안전·날씨 데이터로 칸마다 위험 점수를 매겨 안전경로를 따로 찾는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

  // 항구 목록(이름·위도·경도)을 DB 에서 읽어 오는 기존 서비스를 그대로 빌려 쓴다.
  private final MainService mainService;

  // 배 속력 (노트). 1노트 = 1시간에 1해리 가는 속도
  // 화물선·유조선 기준 12노트로 잡았다. 나중에 선종을 고를 수 있게 바꿀 예정.
  private static final double SPEED_KNOT = 12.0;

  // 지구 반지름 (km). 두 지점 사이 거리를 구할 때 쓴다.
  private static final double EARTH_RADIUS_KM = 6371.0;


  // 출발 항구 이름, 도착 항구 이름을 받아서 경로를 만들어 돌려준다.
  public RouteDTO findRoute(String fromName, String toName) {

    RouteDTO result = new RouteDTO();

    // ---------- 1. 항구 이름으로 좌표 찾기 ----------
    PortDTO from = findPortByName(fromName);
    PortDTO to = findPortByName(toName);

    // 이름에 해당하는 항구가 없으면 여기서 멈추고 사유를 담아 돌려준다.
    if (from == null) {
      result.setError("출발 항구를 찾을 수 없습니다: " + fromName);
      return result;
    }
    if (to == null) {
      result.setError("도착 항구를 찾을 수 없습니다: " + toName);
      return result;
    }
    if (fromName.equals(toName)) {
      result.setError("출발 항구와 도착 항구가 같습니다");
      return result;
    }

    result.setFrom(from);
    result.setTo(to);

    // ---------- 2. 경로 만들기 ----------
    // 지금은 출발점 -> 도착점 직선 하나뿐이다.
    // 3단계에서 이 부분이 A* 알고리즘 호출로 바뀐다.
    RoutePathDTO shortest = makeStraightPath(from, to);

    result.setShortest(shortest);

    // 안전경로도 1단계에서는 같은 직선을 넣어 둔다.
    // 4단계에서 위험도를 반영한 다른 경로로 바뀔 예정.
    result.setSafe(makeStraightPath(from, to));

    log.info("경로 계산: {} -> {} ({}km)", fromName, toName, shortest.getKm());

    return result;
  }


  // 항구 이름으로 항구 정보(위도·경도)를 찾는다. 없으면 null 을 돌려준다.
  private PortDTO findPortByName(String name) {
    List<PortDTO> ports = mainService.getPortList();
    for (PortDTO port : ports) {
      if (port.getName().equals(name)) {
        return port;
      }
    }
    return null;
  }


  // 두 항구를 잇는 직선 경로를 만든다. (1단계용 임시)
  private RoutePathDTO makeStraightPath(PortDTO from, PortDTO to) {

    // 경로가 지나는 점들을 담을 목록
    List<double[]> coords = new ArrayList<>();

    // 출발점 [위도, 경도]
    coords.add(new double[]{from.getLat(), from.getLng()});
    // 도착점 [위도, 경도]
    coords.add(new double[]{to.getLat(), to.getLng()});

    // 거리 계산
    double km = distanceKm(from.getLat(), from.getLng(), to.getLat(), to.getLng());

    RoutePathDTO path = new RoutePathDTO();
    path.setCoords(coords);
    path.setKm(round1(km));
    path.setNm(round1(km / 1.852));                       // km -> 해리
    path.setHours(round1((km / 1.852) / SPEED_KNOT));     // 해리 / 노트 = 시간

    return path;
  }


  // 두 지점 사이의 거리를 km 로 구한다. (하버사인 공식)
  //
  // 지구는 둥글기 때문에 평면에서처럼 피타고라스로 구하면 먼 거리일수록 오차가 커진다.
  // 하버사인은 구(球) 위의 두 점 사이 최단 거리를 구하는 공식이다.
  private double distanceKm(double lat1, double lng1, double lat2, double lng2) {

    // 각도(도) 를 라디안으로 바꾼다. 삼각함수는 라디안을 쓰기 때문.
    double radLat1 = Math.toRadians(lat1);
    double radLat2 = Math.toRadians(lat2);
    double diffLat = Math.toRadians(lat2 - lat1);
    double diffLng = Math.toRadians(lng2 - lng1);

    // 하버사인 공식
    double a = Math.sin(diffLat / 2) * Math.sin(diffLat / 2)
             + Math.cos(radLat1) * Math.cos(radLat2)
             * Math.sin(diffLng / 2) * Math.sin(diffLng / 2);

    double c = 2 * Math.asin(Math.sqrt(a));

    return EARTH_RADIUS_KM * c;
  }


  // 소수점 첫째 자리까지만 남긴다 (예: 45.2847 -> 45.3)
  private double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }
}
