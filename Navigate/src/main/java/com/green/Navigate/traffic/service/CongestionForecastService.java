package com.green.Navigate.traffic.service;

import com.green.Navigate.main.service.NearPortService;
import com.green.Navigate.mtis.ForecastCache;
import com.green.Navigate.mtis.MtisClient;
import com.green.Navigate.mtis.MtisGrid;
import com.green.Navigate.traffic.dto.CongestionForecastDTO;
import com.green.Navigate.traffic.dto.CongestionGridDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 해상교통 혼잡 예보 (MTIS #259) — 오늘~모레, 1시간 단위, 4단계 격자(실시간 교통과 같은 칸)
// 교통현황에서 미래 시각을 고르면 실시간 대신 이것을 보여 줌. 사용자가 고른 시각만 받고 같은 날은 저장본 사용
@Slf4j
@Service
public class CongestionForecastService {

  private static final List<String> LEVELS = List.of("낮음", "보통", "높음", "매우높음");

  private final MtisClient mtisClient;
  private final NearPortService nearPortService;
  private final ForecastCache<CongestionForecastDTO> cache;

  public CongestionForecastService(MtisClient mtisClient, NearPortService nearPortService, JsonMapper jsonMapper,
                                   @Value("${mtis.cache-dir:cache}") String cacheDir) {
    this.mtisClient = mtisClient;
    this.nearPortService = nearPortService;
    this.cache = new ForecastCache<>(Path.of(cacheDir, "congestion"), CongestionForecastDTO.class, jsonMapper);
  }

  // date = yyyyMMdd (오늘~모레), time = 00~23 (HH 또는 HH00)
  public CongestionForecastDTO get(String date, String time) {
    String hh = time == null || time.length() < 2 ? "" : time.substring(0, 2);
    if (!hh.matches("\\d{2}") || Integer.parseInt(hh) > 23) {
      return failed(date, hh + "00", "시각 형식이 맞지 않습니다 (00~23)");
    }
    String fcstTime = hh + "00";
    String check = ForecastCache.checkDate(date);
    if (check != null) {
      return failed(date, fcstTime, check);
    }
    return cache.get(date + "_" + fcstTime, () -> fetch(date, fcstTime), msg -> failed(date, fcstTime, msg));
  }

  private CongestionForecastDTO fetch(String date, String fcstTime) {
    JsonNode body = mtisClient.get("259_congestion", "/eopt/api/sea-tfc-congst-fcst-Info",
        Map.of("pageNo", "1", "numOfRows", "6000", "fcstDate", date, "fcstTime", fcstTime, "gridLevel", "4"));

    Map<String, Integer> counts = new LinkedHashMap<>();
    LEVELS.forEach(l -> counts.put(l, 0));
    List<CongestionGridDTO> grids = new ArrayList<>();
    CongestionGridDTO top = null;
    String created = null;

    for (JsonNode r : MtisClient.rows(body)) {
      Double lat = MtisClient.num(r, "latitude");
      Double lng = MtisClient.num(r, "longitude");
      if (lat == null || lng == null || !MtisGrid.inKorea(lat, lng)) {
        continue;
      }
      CongestionGridDTO g = new CongestionGridDTO();
      g.setId(r.path("gridId").asString(""));
      g.setLat(lat);
      g.setLng(lng);
      g.setIndex(MtisClient.num(r, "congestionIndex"));
      g.setLevel(r.path("congestionLevel").asString(""));
      g.setWind(MtisClient.num(r, "forecastWindSpeed"));
      g.setWave(MtisClient.num(r, "forecastWaveHeight"));
      g.setNear(nearPortService.describe(lat, lng));
      if (created == null) {
        created = r.path("fcstCreateDateTime").asString(null);
      }
      counts.merge(g.getLevel(), 1, Integer::sum);
      if (top == null || (g.getIndex() != null && (top.getIndex() == null || g.getIndex() > top.getIndex()))) {
        top = g;
      }
      grids.add(g);
    }

    CongestionForecastDTO dto = new CongestionForecastDTO();
    dto.setFcstDate(date);
    dto.setFcstTime(fcstTime);
    dto.setCreated(created);
    dto.setFetchedDay(ForecastCache.today());
    dto.setCounts(counts);
    dto.setTop(top);
    dto.setGrids(grids);
    log.info("혼잡 예보 {} {}: 매우높음 {} · 높음 {} · 보통 {}칸 (오늘 MTIS {}건)", date, fcstTime,
        counts.get("매우높음"), counts.get("높음"), counts.get("보통"), mtisClient.getCallsToday());
    return dto;
  }

  private static CongestionForecastDTO failed(String date, String fcstTime, String message) {
    CongestionForecastDTO dto = new CongestionForecastDTO();
    dto.setFcstDate(date);
    dto.setFcstTime(fcstTime);
    dto.setError(message);
    return dto;
  }
}
