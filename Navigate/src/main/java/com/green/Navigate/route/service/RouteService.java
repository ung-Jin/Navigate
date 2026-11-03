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

  // 칸마다 위험 점수를 매기는 곳 (교통·사고위험 예보·파고·풍속)
  private final RiskMap riskMap;

  // 배 속력 (노트). 1노트 = 1시간에 1해리 가는 속도
  // 화물선·유조선 기준 12노트로 잡았다. 나중에 선종을 고를 수 있게 바꿀 예정.
  private static final double SPEED_KNOT = 12.0;

  // 안전경로가 위험을 얼마나 피할지
  //
  // 간선 비용 = 거리 × (1 + alpha × 위험도) 이므로, 3 이면 가장 위험한 칸이 4배까지 비싸진다.
  // 너무 작으면 최소경로와 똑같아지고, 너무 크면 조금만 위험해도 크게 돌아간다.
  private static final double SAFE_ALPHA = 3.0;

  // 위험도가 이 값 이상이면 '고위험 구간'으로 센다
  private static final double HIGH_RISK = 0.6;

  // 위험 노출량을 잴 때 경로를 몇 km 간격으로 짚어 볼지
  private static final double SAMPLE_KM = 0.5;


  // 출발 항구 이름, 도착 항구 이름을 받아서 경로를 만들어 돌려준다.
  //
  // date = yyyyMMdd, time = HH — 출항 시각. 둘 다 null 이면 지금 기준.
  // 시각을 바꾸면 그 시각의 예보로 위험도를 매기므로, 언제 떠나는 게 안전한지 견줄 수 있다.
  public RouteDTO findRoute(String fromName, String toName, String date, String time) {

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

    // ---------- 3. 위험도 지도 준비 ----------
    // 교통·사고위험 예보·파고·풍속을 칸별 점수(0~1)로 합친 것
    long startedAt = System.currentTimeMillis();
    RiskMap.Snapshot risk = riskMap.load(date, time);

    // ---------- 4. 바닷길 두 개 찾기 ----------
    // 같은 A* 를 alpha 만 바꿔 두 번 돌린다.
    //   alpha = 0     -> 위험 무시, 거리만  = 최소경로
    //   alpha = ALPHA -> 위험한 곳을 돌아감 = 안전경로
    List<double[]> shortPath = aStar.find(startCell, goalCell);
    if (shortPath == null) {
      result.setError("두 항구를 잇는 바닷길을 찾지 못했습니다");
      return result;
    }
    List<double[]> safePath = aStar.find(startCell, goalCell, risk, SAFE_ALPHA);

    RoutePathDTO shortest = makePath(from, to, shortPath, risk);
    RoutePathDTO safe = safePath == null ? shortest : makePath(from, to, safePath, risk);

    // 돌아갔는데 더 안전하지 않다면 최소경로가 곧 안전경로다
    if (safe.getExposure() >= shortest.getExposure()) {
      safe = shortest;
    }

    result.setShortest(shortest);
    result.setSafe(safe);
    result.setAlpha(SAFE_ALPHA);
    result.setSources(risk.getSources());

    // ---------- 5. 두 경로 견주기 ----------
    boolean same = safe == shortest;
    result.setSame(same);
    if (!same) {
      result.setKmPct(percentChange(safe.getKm(), shortest.getKm()));
      result.setExposurePct(percentChange(safe.getExposure(), shortest.getExposure()));
    }

    log.info("경로 계산: {} -> {} · 최소 {}km(노출 {}) · 안전 {}km(노출 {}) · {} · {}ms",
        fromName, toName, shortest.getKm(), shortest.getExposure(),
        safe.getKm(), safe.getExposure(), same ? "같음" : "갈라짐",
        System.currentTimeMillis() - startedAt);

    return result;
  }


  // 최소경로 대비 몇 % 늘었는지 (+) 또는 줄었는지 (-)
  private Double percentChange(double safeValue, double shortValue) {
    if (shortValue <= 0) {
      return null;
    }
    return round1((safeValue - shortValue) / shortValue * 100);
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
  private RoutePathDTO makePath(PortDTO from, PortDTO to, List<double[]> seaPath, RiskMap.Snapshot risk) {

    // 경로가 지나는 점들 [위도, 경도]
    List<double[]> coords = new ArrayList<>();
    coords.add(new double[]{from.getLat(), from.getLng()});   // 출발 항구
    coords.addAll(seaPath);                                   // 바닷길
    coords.add(new double[]{to.getLat(), to.getLng()});       // 도착 항구

    // 경로를 따라가며 거리와 위험 지표를 함께 잰다.
    //
    // 지점 사이가 수십 km 씩 되기도 해서, 끝점만 보면 중간에 지나는 위험한 칸을 놓친다.
    // 그래서 0.5km 간격으로 짚어 가며 그 자리의 위험도를 센다.
    double km = 0;
    double exposure = 0;     // Σ(위험도 × km)
    double highKm = 0;       // 위험도 0.6 이상인 구간 길이
    double maxRisk = 0;

    for (int i = 1; i < coords.size(); i++) {
      double[] a = coords.get(i - 1);
      double[] b = coords.get(i);
      double segment = GeoUtil.distanceKm(a, b);
      km += segment;

      int steps = Math.max(1, (int) (segment / SAMPLE_KM));
      double piece = segment / steps;

      for (int s = 0; s < steps; s++) {
        double t = (s + 0.5) / steps;                   // 조각의 한가운데
        double lat = a[0] + (b[0] - a[0]) * t;
        double lng = a[1] + (b[1] - a[1]) * t;
        double r = risk.at(seaGrid.row(lat), seaGrid.col(lng));

        exposure += r * piece;
        if (r >= HIGH_RISK) {
          highKm += piece;
        }
        maxRisk = Math.max(maxRisk, r);
      }
    }

    RoutePathDTO path = new RoutePathDTO();
    path.setCoords(coords);
    path.setKm(round1(km));
    path.setNm(round1(km / 1.852));                       // km -> 해리
    path.setHours(round1((km / 1.852) / SPEED_KNOT));     // 해리 / 노트 = 시간
    path.setExposure(round1(exposure));
    path.setHighKm(round1(highKm));
    path.setMaxRisk(Math.round(maxRisk * 100) / 100.0);

    return path;
  }


  // 소수점 첫째 자리까지만 남긴다 (예: 45.2847 -> 45.3)
  private double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }
}
