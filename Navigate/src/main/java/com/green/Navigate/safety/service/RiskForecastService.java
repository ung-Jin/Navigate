package com.green.Navigate.safety.service;

import com.green.Navigate.main.service.NearPortService;
import com.green.Navigate.mtis.ForecastCache;
import com.green.Navigate.mtis.MtisClient;
import com.green.Navigate.safety.dto.RiskForecastDTO;
import com.green.Navigate.safety.dto.RiskGridDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 해양사고 위험 예보 (MTIS #261) — 오늘~모레, 3시간 단위
// 사용자가 고른 시각만 받고, 같은 날에는 저장해 둔 것을 씀 (ForecastCache)
@Slf4j
@Service
public class RiskForecastService {

  private static final List<String> LEVELS = List.of("낮음", "보통", "주의", "경계");

  private final MtisClient mtisClient;
  private final NearPortService nearPortService;
  private final ForecastCache<RiskForecastDTO> cache;

  public RiskForecastService(MtisClient mtisClient, NearPortService nearPortService, JsonMapper jsonMapper,
                             @Value("${mtis.cache-dir:cache}") String cacheDir) {
    this.mtisClient = mtisClient;
    this.nearPortService = nearPortService;
    this.cache = new ForecastCache<>(Path.of(cacheDir, "risk"), RiskForecastDTO.class, jsonMapper);
  }

  // date = yyyyMMdd (오늘~모레), time = 00~23 (HH 또는 HH00) — 3시간 단위로 내려 맞춤 (14시 → 12시 예보)
  public RiskForecastDTO get(String date, String time) {
    String hh = time == null || time.length() < 2 ? "" : time.substring(0, 2);
    if (!hh.matches("\\d{2}") || Integer.parseInt(hh) > 23) {
      return failed(date, hh + "00", "시각 형식이 맞지 않습니다 (00~23)");
    }
    String fcstTime = String.format("%02d00", Integer.parseInt(hh) / 3 * 3);
    String check = ForecastCache.checkDate(date);
    if (check != null) {
      return failed(date, fcstTime, check);
    }
    return cache.get(date + "_" + fcstTime, () -> fetch(date, fcstTime), msg -> failed(date, fcstTime, msg));
  }

  private RiskForecastDTO fetch(String date, String fcstTime) {
    JsonNode body = mtisClient.get("261_risk", "/eopt/api/sea-acdnt-risk-fcst-info",
        Map.of("pageNo", "1", "numOfRows", "6000", "fcstDate", date, "fcstTime", fcstTime));
    RiskForecastDTO dto = build(body, date, fcstTime);
    log.info("사고위험 예보 {} {}: 경계 {} · 주의 {} · 보통 {}칸 (오늘 MTIS {}건)", date, fcstTime,
        dto.getCounts().get("경계"), dto.getCounts().get("주의"), dto.getCounts().get("보통"), mtisClient.getCallsToday());
    return dto;
  }

  private RiskForecastDTO build(JsonNode body, String date, String fcstTime) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    LEVELS.forEach(l -> counts.put(l, 0));
    List<RiskGridDTO> grids = new ArrayList<>();
    RiskGridDTO top = null;
    double maxWave = 0, maxWind = 0;
    String created = null;

    for (JsonNode r : MtisClient.rows(body)) {
      RiskGridDTO g = new RiskGridDTO();
      g.setId(r.path("gridId").asString(""));
      g.setLat(MtisClient.num(r, "latitude"));
      g.setLng(MtisClient.num(r, "longitude"));
      g.setLevel(r.path("accidentRiskLevel").asString(""));
      g.setIndex(MtisClient.num(r, "accidentRiskIndex"));
      g.setWave(MtisClient.num(r, "forecastWaveHeight"));
      g.setWind(MtisClient.num(r, "forecastWindSpeed"));
      g.setWindDir(MtisClient.num(r, "forecastWindDirection"));
      if (g.getLat() == null || g.getLng() == null) {
        continue;
      }
      if (created == null) {
        created = r.path("fcstCreateDateTime").asString(null);
      }
      counts.merge(g.getLevel(), 1, Integer::sum);
      maxWave = Math.max(maxWave, g.getWave() == null ? 0 : g.getWave());
      maxWind = Math.max(maxWind, g.getWind() == null ? 0 : g.getWind());
      if (top == null || (g.getIndex() != null && (top.getIndex() == null || g.getIndex() > top.getIndex()))) {
        top = g;
      }
      if (!"낮음".equals(g.getLevel())) {
        grids.add(g);
      }
    }
    grids.sort(Comparator.comparing(RiskGridDTO::getIndex, Comparator.nullsFirst(Comparator.naturalOrder())));  // 위험한 칸이 위에 그려지게
    grids.forEach(g -> g.setNear(nearPortService.describe(g.getLat(), g.getLng())));
    if (top != null && top.getNear() == null) {
      top.setNear(nearPortService.describe(top.getLat(), top.getLng()));
    }

    RiskForecastDTO dto = new RiskForecastDTO();
    dto.setFcstDate(date);
    dto.setFcstTime(fcstTime);
    dto.setCreated(created);
    dto.setFetchedDay(ForecastCache.today());
    dto.setCounts(counts);
    dto.setMaxWave(maxWave);
    dto.setMaxWind(maxWind);
    dto.setTop(top);
    dto.setGrids(grids);
    return dto;
  }

  private static RiskForecastDTO failed(String date, String fcstTime, String message) {
    RiskForecastDTO dto = new RiskForecastDTO();
    dto.setFcstDate(date);
    dto.setFcstTime(fcstTime);
    dto.setError(message);
    return dto;
  }
}
