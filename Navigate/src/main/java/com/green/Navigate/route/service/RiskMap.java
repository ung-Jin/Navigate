package com.green.Navigate.route.service;

import com.green.Navigate.safety.dto.RiskForecastDTO;
import com.green.Navigate.safety.dto.RiskGridDTO;
import com.green.Navigate.safety.service.RiskForecastService;
import com.green.Navigate.traffic.dto.CongestionForecastDTO;
import com.green.Navigate.traffic.dto.CongestionGridDTO;
import com.green.Navigate.traffic.dto.GridDTO;
import com.green.Navigate.traffic.dto.RealtimeDTO;
import com.green.Navigate.traffic.service.CongestionForecastService;
import com.green.Navigate.traffic.service.TrafficService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


// 칸마다 위험 점수(0~1)를 매긴다.
//
// 세 가지를 합친다. 모두 팀원들이 이미 받아 두고 있는 자료라 새로 부를 API 가 없다.
//   교통      TrafficService / CongestionForecastService  — 배가 몰리는 곳은 충돌 위험
//   사고위험  RiskForecastService                          — KOMSA 가 종합해 낸 등급
//   기상      위 두 자료에 들어 있는 파고·풍속              — 선종 한계를 넘으면 위험
//
// 위험 자료는 MTIS 해양격자(2.5km) 기준이고 경로는 500m 칸을 쓴다.
// 2단계에서 격자를 MTIS 에 맞춰 둔 덕분에 칸 번호를 5로 나누기만 하면 찾을 수 있다.
//
// [나중에 더할 수 있는 것] 과거 사고 이력(ML), 암초·장애물, 수심
//   아래 riskAt() 에 항목을 하나 더하고 가중치를 주면 된다. 다른 코드는 바뀌지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskMap {

  private final TrafficService trafficService;
  private final CongestionForecastService congestionForecastService;
  private final RiskForecastService riskForecastService;

  // ---------- 가중치 ----------
  //
  // 처음에는 상식으로 35/35/30 으로 잡았다가, MTIS 사고다발 격자(#243)를 정답지로 삼아 조정했다.
  // 검증 방법과 재현은 tools/weight_check.py 참고.
  //
  //   전체 3,401칸을 위험도 순으로 줄 세우고, 사고다발 26칸이 몇 등인지 쟀다.
  //   (사고다발을 계산에 넣고 채점하면 순환 논리가 되므로 정답지로만 썼다)
  //
  //     35/35/30  중앙 440등 (상위 13%) · AUC 0.798
  //     50/30/20  중앙 172등 (상위  5%) · AUC 0.856   <- 지금
  //
  //   교통 비중을 올릴수록 사고다발 칸이 앞으로 왔다. 사고다발 지점은 "배는 몰리는데
  //   예보 등급은 낮은 곳"이 많았기 때문이다 (사고 유형 1위가 충돌인 것과도 맞는다).
  //   다만 교통만 쓰면 AUC 0.649 로 떨어진다 — 교통이 높은 일반 칸까지 올라와 묻힌다.
  //
  // 기상 0.20 은 검증하지 못한 값이다. 측정한 날 바다가 잔잔해(최대 파고 1.05m)
  // 기상 점수가 0 이 아닌 칸이 6,497개 중 2개뿐이었다. 날씨가 나쁜 날 다시 재야 한다.
  private static final double W_TRAFFIC = 0.50;    // 교통 혼잡
  private static final double W_FORECAST = 0.30;   // 사고위험 예보
  private static final double W_WEATHER = 0.20;    // 파고·풍속

  // ---------- 화물선·유조선 기상 한계 ----------
  // 팀원이 PortWeather.level() 에 쓴 기준(파고 3m·풍속 14m/s)은 여객선 통제 기준이다.
  // 화물선·유조선은 더 큰 배라 한계를 높게 잡았다. 실제 선박 규격으로 검증이 필요한 임시값.
  private static final double WAVE_LIMIT_M = 4.0;
  private static final double WIND_LIMIT_MS = 20.0;

  // ---------- MTIS 해양격자 ----------
  private static final double MTIS_CELL = 0.025;
  private static final int MTIS_COLS = 320;
  private static final int SCALE = (int) Math.round(MTIS_CELL / SeaGrid.CELL);   // 5

  // 사고위험 예보 칸의 반폭 (위도, 경도) — 약 4.2 × 4.1km. safety.js 와 같은 값
  private static final double RISK_HALF_LAT = 0.0187;
  private static final double RISK_HALF_LNG = 0.0225;

  // 등급을 0~1 점수로
  private static final Map<String, Double> LEVEL_SCORE = Map.of(
      "낮음", 0.0, "보통", 0.33, "높음", 0.66, "매우높음", 1.0,
      "주의", 0.66, "경계", 1.0);


  // 한 시각의 위험도 지도. A* 가 칸마다 이 값을 물어본다.
  public static class Snapshot {
    private final Map<Integer, Double> byMtisCell;
    private final List<String> sources;

    Snapshot(Map<Integer, Double> byMtisCell, List<String> sources) {
      this.byMtisCell = byMtisCell;
      this.sources = sources;
    }

    // 500m 칸 (행, 열) 의 위험도 0~1
    public double at(int row, int col) {
      int key = (row / SCALE) * MTIS_COLS + (col / SCALE);
      Double v = byMtisCell.get(key);
      return v == null ? 0.0 : v;
    }

    // 어떤 자료를 썼는지 (화면에 표시)
    public List<String> getSources() {
      return sources;
    }
  }


  // 한 번 조립한 위험도 지도를 담아 두는 곳.
  //
  // 예보 원본은 팀원 서비스가 이미 저장해 두지만, 그것을 칸별 점수로 합치는 일(수천 칸을 돌며
  // 더하고, 사고위험 예보의 큰 칸을 잘게 칠하는 것)은 매번 다시 하고 있었다.
  // 같은 시각이면 어느 경로를 찾든 위험도 지도는 똑같으므로 담아 두고 다시 쓴다.
  private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

  // '지금' 기준 지도를 만든 시각 (실시간 교통이 10분마다 갱신되므로 오래 쓰면 안 된다)
  private volatile long nowBuiltAt = 0;

  // '지금' 기준 지도를 다시 만들 간격 (분)
  private static final long NOW_CACHE_MINUTES = 3;


  // date = yyyyMMdd, time = HH (둘 다 null 이면 지금)
  public Snapshot load(String date, String time) {

    boolean now = date == null || time == null;

    // 담아 둔 것이 있으면 그대로 쓴다.
    // '지금' 은 실시간 교통이 계속 바뀌므로 몇 분만 쓰고 버린다.
    String key = now ? "now" : date + "_" + time;
    Snapshot kept = cache.get(key);
    if (kept != null) {
      boolean stale = now && System.currentTimeMillis() - nowBuiltAt > NOW_CACHE_MINUTES * 60_000;
      if (!stale) {
        return kept;
      }
    }

    Snapshot built = build(date, time, now);
    cache.put(key, built);
    if (now) {
      nowBuiltAt = System.currentTimeMillis();
    }
    return built;
  }


  private Snapshot build(String date, String time, boolean now) {

    String d = now ? today() : date;
    String t = now ? String.format("%02d", java.time.LocalDateTime.now().getHour()) : time;

    Map<Integer, Double> traffic = new HashMap<>();
    Map<Integer, Double> forecast = new HashMap<>();
    Map<Integer, Double> weather = new HashMap<>();
    List<String> sources = new ArrayList<>();

    // ---------- 1. 교통 ----------
    // 지금이면 실시간 밀집도를, 미래 시각이면 혼잡 예보를 쓴다.
    if (now) {
      RealtimeDTO rt = trafficService.getRealtime();
      if (rt.getGrids() != null) {
        for (GridDTO g : rt.getGrids()) {
          put(traffic, g.getLat(), g.getLng(), densityScore(g.getDnsty()));
        }
        sources.add("교통: 실시간 밀집도 " + (rt.getRegDt() == null ? "" : rt.getRegDt()));
      }
    }

    // ---------- 2. 혼잡 예보 (교통 보강 + 파고·풍속) ----------
    CongestionForecastDTO cg = congestionForecastService.get(d, t);
    if (cg.getGrids() != null) {
      for (CongestionGridDTO g : cg.getGrids()) {
        if (!now) {
          put(traffic, g.getLat(), g.getLng(), LEVEL_SCORE.getOrDefault(g.getLevel(), 0.0));
        }
        put(weather, g.getLat(), g.getLng(), weatherScore(g.getWave(), g.getWind()));
      }
      sources.add("혼잡 예보 " + cg.getFcstDate() + " " + cg.getFcstTime());
    } else if (cg.getError() != null) {
      log.warn("혼잡 예보를 쓰지 못했습니다: {}", cg.getError());
    }

    // ---------- 3. 사고위험 예보 ----------
    // 격자가 4.2km 라 2.5km 칸과 딱 나뉘지 않는다. 칸 하나가 덮는 범위를 칠한다.
    RiskForecastDTO rk = riskForecastService.get(d, t);
    if (rk.getGrids() != null) {
      for (RiskGridDTO g : rk.getGrids()) {
        double score = LEVEL_SCORE.getOrDefault(g.getLevel(), 0.0);
        paint(forecast, g.getLat(), g.getLng(), score);
        paint(weather, g.getLat(), g.getLng(), weatherScore(g.getWave(), g.getWind()));
      }
      sources.add("사고위험 예보 " + rk.getFcstDate() + " " + rk.getFcstTime());
    } else if (rk.getError() != null) {
      log.warn("사고위험 예보를 쓰지 못했습니다: {}", rk.getError());
    }

    // ---------- 4. 합치기 ----------
    Map<Integer, Double> total = new HashMap<>();
    for (Integer key : union(traffic, forecast, weather)) {
      double v = W_TRAFFIC * traffic.getOrDefault(key, 0.0)
               + W_FORECAST * forecast.getOrDefault(key, 0.0)
               + W_WEATHER * weather.getOrDefault(key, 0.0);
      if (v > 0.01) {
        total.put(key, Math.min(1.0, v));
      }
    }

    if (sources.isEmpty()) {
      sources.add("위험 자료를 받지 못해 거리만으로 계산했습니다");
    }
    log.info("위험도 지도: 칸 {}개 · {}", total.size(), String.join(" · ", sources));

    return new Snapshot(total, sources);
  }


  // ---------- 점수 매기기 ----------

  // 밀집도(%) -> 0~1. MTIS 혼잡 단계 경계(1·10·20%)에 맞춤
  private double densityScore(Double density) {
    if (density == null) {
      return 0.0;
    }
    return density < 1 ? 0.0 : density < 10 ? 0.33 : density < 20 ? 0.66 : 1.0;
  }

  // 파고·풍속 -> 0~1
  //
  // 파고 2m 와 4m 가 2배 위험한 것이 아니라, 한계를 넘으면 급격히 위험해진다.
  // 그래서 한계의 절반까지는 0, 한계에서 1 이 되도록 하고, 넘으면 1 로 둔다.
  private double weatherScore(Double wave, Double wind) {
    double w1 = ratio(wave, WAVE_LIMIT_M);
    double w2 = ratio(wind, WIND_LIMIT_MS);
    return Math.max(w1, w2);                       // 둘 중 나쁜 쪽
  }

  private double ratio(Double value, double limit) {
    if (value == null || value <= limit / 2) {
      return 0.0;
    }
    return Math.min(1.0, (value - limit / 2) / (limit / 2));
  }


  // ---------- 격자에 담기 ----------

  // 좌표가 든 MTIS 칸에 점수를 넣는다 (이미 값이 있으면 큰 쪽을 남김)
  private void put(Map<Integer, Double> map, Double lat, Double lng, double score) {
    if (lat == null || lng == null || score <= 0) {
      return;
    }
    map.merge(mtisKey(lat, lng), score, Math::max);
  }

  // 사고위험 예보처럼 칸이 큰 자료는, 그 칸이 덮는 MTIS 칸들을 모두 칠한다
  private void paint(Map<Integer, Double> map, Double lat, Double lng, double score) {
    if (lat == null || lng == null || score <= 0) {
      return;
    }
    for (double la = lat - RISK_HALF_LAT; la <= lat + RISK_HALF_LAT; la += MTIS_CELL / 2) {
      for (double ln = lng - RISK_HALF_LNG; ln <= lng + RISK_HALF_LNG; ln += MTIS_CELL / 2) {
        map.merge(mtisKey(la, ln), score, Math::max);
      }
    }
  }

  private int mtisKey(double lat, double lng) {
    int r = (int) Math.floor((lat - SeaGrid.LAT0) / MTIS_CELL);
    int c = (int) Math.floor((lng - SeaGrid.LON0) / MTIS_CELL);
    return r * MTIS_COLS + c;
  }

  @SafeVarargs
  private List<Integer> union(Map<Integer, Double>... maps) {
    List<Integer> keys = new ArrayList<>();
    for (Map<Integer, Double> m : maps) {
      keys.addAll(m.keySet());
    }
    return keys.stream().distinct().toList();
  }

  private String today() {
    return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
  }
}
