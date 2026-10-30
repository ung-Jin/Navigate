package com.green.Navigate.route.service;

import com.green.Navigate.main.dto.PortDTO;
import com.green.Navigate.main.service.MainService;
import com.green.Navigate.route.dto.RouteDTO;
import com.green.Navigate.route.dto.RoutePathDTO;
import com.green.Navigate.route.util.GeoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


// 경로 계산 담당
//
// [1단계 완료] 출발 항구와 도착 항구를 잇는 경로를 화면까지 연결.
// [2단계 완료] 바다/육지 격자(SeaGrid)를 만들어 "이 칸이 바다인가"를 알 수 있게 함.
// [3단계 - 지금] A* 알고리즘으로 바다 칸만 밟아 육지를 피하는 최소경로를 찾는다.
// [4단계 예정] 교통·안전·날씨 데이터로 칸마다 위험 점수를 매겨 안전경로를 따로 찾는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

  // 항구 목록(이름·위도·경도)을 DB 에서 읽어 오는 기존 서비스를 그대로 빌려 쓴다.
  private final MainService mainService;

  // 바다/육지 격자. 항구가 바다에 닿아 있는지 확인하고, 항구를 가까운 바다 칸으로 옮기는 데 쓴다.
  private final SeaGrid seaGrid;

  // 바다 칸만 밟아 최단 경로를 찾는 알고리즘
  private final AStar aStar;

  // 배 속력 (노트). 1노트 = 1시간에 1해리 가는 속도
  // 화물선·유조선 기준 12노트로 잡았다. 나중에 선종을 고를 수 있게 바꿀 예정.
  private static final double SPEED_KNOT = 12.0;


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

    // ---------- 2. 항구를 바다 칸으로 옮기기 ----------
    // 항구는 땅에 붙어 있어서 그 칸이 '통과 불가'로 나오는 경우가 많다 (부산항이 있는 칸은 바다가 7%뿐).
    // 그래서 항구 근처에서 가장 가까운 바다 칸을 찾아 경로의 시작/끝으로 삼는다.
    //
    // 무역항이라도 내륙 수로에 있으면 화물선·유조선이 드나들 수 없다.
    // 예: 서울항은 한강 여의도 부근이라 바다까지 29km 떨어져 있고, 그 사이가 전부 육지다.
    //     (실제로도 경인아라뱃길은 수심 6.3m·4,000톤급 한계라 대형선이 못 지나간다)
    //
    // nearestNavigable 이 null = 그 항구 15km 안에 배가 지날 수 있는 바다 칸이 없다는 뜻.
    // 항구 이름을 직접 적어 두지 않고 격자로 판정하므로, 나중에 다른 내륙 항만이 늘어도 그대로 걸러진다.
    int[] startCell = seaGrid.nearestNavigable(from.getLat(), from.getLng());
    if (startCell == null) {
      result.setError(inlandMessage(from.getName()));
      return result;
    }
    int[] goalCell = seaGrid.nearestNavigable(to.getLat(), to.getLng());
    if (goalCell == null) {
      result.setError(inlandMessage(to.getName()));
      return result;
    }

    result.setFrom(from);
    result.setTo(to);

    // ---------- 3. 바닷길 찾기 (A*) ----------
    long startedAt = System.currentTimeMillis();

    List<double[]> seaPath = aStar.find(startCell, goalCell);
    if (seaPath == null) {
      result.setError("두 항구를 잇는 바닷길을 찾지 못했습니다");
      return result;
    }

    RoutePathDTO shortest = makePath(from, to, seaPath);
    result.setShortest(shortest);

    // 안전경로는 아직 최소경로와 같다.
    // 4단계에서 칸마다 위험 점수를 매겨, 위험한 곳을 돌아가는 다른 경로로 바뀐다.
    result.setSafe(makePath(from, to, seaPath));

    log.info("경로 계산: {} -> {} ({}km, 지점 {}개, {}ms)",
        fromName, toName, shortest.getKm(), shortest.getCoords().size(),
        System.currentTimeMillis() - startedAt);

    return result;
  }


  // 내륙 항구 안내 문구를 만든다.
  //
  // 이름 뒤에 붙는 조사가 받침에 따라 달라진다 ("서울항은", "여의도는").
  // 한글 음절은 유니코드에서 가나다 순으로 이어져 있고, 한 글자가 (초성, 중성, 종성) 조합이라
  // (글자 - '가') 를 28(종성 개수)로 나눈 나머지가 0이면 받침이 없는 글자다.
  private String inlandMessage(String portName) {
    char last = portName.charAt(portName.length() - 1);

    String particle;
    if (last < '가' || last > '힣') {
      particle = "은";                                   // 한글이 아니면 그냥 "은"
    } else {
      particle = (last - '가') % 28 == 0 ? "는" : "은";   // 나머지 0 = 받침 없음
    }

    return portName + particle + " 내륙에 있어 화물선·유조선이 드나들 수 없습니다";
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


  // A* 가 찾은 바닷길 앞뒤에 항구 좌표를 붙이고, 거리·시간을 계산한다.
  //
  // A* 는 바다 칸 가운데를 이은 경로만 돌려준다.
  // 거기에 실제 항구 위치를 앞뒤로 붙여야 "항구에서 항구까지" 가 된다.
  private RoutePathDTO makePath(PortDTO from, PortDTO to, List<double[]> seaPath) {

    // 경로가 지나는 점들 [위도, 경도]
    List<double[]> coords = new ArrayList<>();
    coords.add(new double[]{from.getLat(), from.getLng()});   // 출발 항구
    coords.addAll(seaPath);                                   // 바닷길
    coords.add(new double[]{to.getLat(), to.getLng()});       // 도착 항구

    // 점과 점 사이 거리를 모두 더해 전체 거리를 구한다.
    double km = 0;
    for (int i = 1; i < coords.size(); i++) {
      km += GeoUtil.distanceKm(coords.get(i - 1), coords.get(i));
    }

    RoutePathDTO path = new RoutePathDTO();
    path.setCoords(coords);
    path.setKm(round1(km));
    path.setNm(round1(km / 1.852));                       // km -> 해리
    path.setHours(round1((km / 1.852) / SPEED_KNOT));     // 해리 / 노트 = 시간

    return path;
  }


  // 소수점 첫째 자리까지만 남긴다 (예: 45.2847 -> 45.3)
  private double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }
}
