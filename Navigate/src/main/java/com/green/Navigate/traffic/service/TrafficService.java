package com.green.Navigate.traffic.service;

import com.green.Navigate.main.service.NearPortService;
import com.green.Navigate.mtis.MtisClient;
import com.green.Navigate.mtis.MtisException;
import com.green.Navigate.mtis.MtisGrid;
import com.green.Navigate.traffic.dto.GridDTO;
import com.green.Navigate.traffic.dto.RealtimeDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TrafficService {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final Duration INTERVAL = Duration.ofMinutes(10);

  private final MtisClient mtisClient;
  private final NearPortService nearPortService;
  private final JsonMapper jsonMapper;
  private final Path latestFile;  // 마지막 스냅샷 (서버를 다시 켜도 10분 안이면 MTIS 를 또 부르지 않음)

  private volatile RealtimeDTO latest;
  private volatile String lastError;

  public TrafficService(MtisClient mtisClient, NearPortService nearPortService, JsonMapper jsonMapper,
                        @Value("${mtis.cache-dir:cache}") String cacheDir) {
    this.mtisClient = mtisClient;
    this.nearPortService = nearPortService;
    this.jsonMapper = jsonMapper;
    this.latestFile = Path.of(cacheDir, "realtime-latest.json");
    loadLatest();
  }

  // 지금 가진 실시간 스냅샷 (수집 실패 사유가 있으면 error 에 담음)
  public RealtimeDTO getRealtime() {
    RealtimeDTO dto = latest == null ? new RealtimeDTO() : latest;
    dto.setError(lastError != null ? lastError : (latest == null ? "아직 수집 전입니다" : null));
    return dto;
  }

  // 10분마다 MTIS #244 실시간 교통량 수집 (서버 켜자마자 1번)
  // 실시간 자료는 지나간 시각을 다시 조회할 수 없으므로 계속 받아 둬야 함
  @Scheduled(initialDelay = 0, fixedDelay = 10 * 60 * 1000)
  public void collectRealtime() {
    if (latest != null && isFresh(latest.getFetchedAt())) {
      return;  // 10분 안에 받은 것이 있으면 건너뜀 (한도 아끼기)
    }
    try {
      JsonNode res = mtisClient.get("244_realtime", "/api/v1/traffic/realtime", Map.of());
      latest = build(res);
      lastError = null;
      saveLatest();
      log.info("실시간 교통 수집: {}칸 · {}척 (기준 {}, 오늘 MTIS {}건)",
          latest.getGrids().size(), latest.getTotalShips(), latest.getRegDt(), mtisClient.getCallsToday());
    } catch (MtisException e) {
      lastError = e.getMessage();
      log.warn("실시간 교통 수집 실패: {}", e.getMessage());
    }
  }

  // MTIS 응답 → 국내 해역 칸만 골라 요약
  private RealtimeDTO build(JsonNode res) {
    List<GridDTO> grids = new ArrayList<>();
    for (JsonNode r : MtisClient.rows(res)) {
      String id = r.path("grid_id").asString("");
      double[] ll = MtisGrid.toLatLng(id);
      if (ll == null || !MtisGrid.inKorea(ll[0], ll[1])) {
        continue;
      }
      GridDTO g = new GridDTO();
      g.setId(id);
      g.setLat(ll[0]);
      g.setLng(ll[1]);
      g.setVmtc(r.path("vmtc").asInt(0));
      g.setDnsty(r.path("dnsty").isNumber() ? r.path("dnsty").asDouble() : null);
      g.setNear(nearPortService.describe(ll[0], ll[1]));
      grids.add(g);
    }
    grids.sort(Comparator.comparing(GridDTO::getVmtc).reversed());  // 선박 많은 칸부터

    RealtimeDTO dto = new RealtimeDTO();
    dto.setRegDt(res.path("regDt").asString(null));
    dto.setFetchedAt(LocalDateTime.now(MtisClient.KST).format(TIME));
    dto.setGrids(grids);
    dto.setTotalShips(grids.stream().mapToInt(GridDTO::getVmtc).sum());
    dto.setShipCells((int) grids.stream().filter(g -> g.getVmtc() > 0).count());
    dto.setTop(grids.isEmpty() ? null : grids.get(0));
    return dto;
  }

  private boolean isFresh(String fetchedAt) {
    try {
      LocalDateTime t = LocalDateTime.parse(fetchedAt, TIME);
      return Duration.between(t, LocalDateTime.now(MtisClient.KST)).compareTo(INTERVAL.minusSeconds(30)) < 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void loadLatest() {
    if (!Files.exists(latestFile)) {
      return;
    }
    try {
      latest = jsonMapper.readValue(latestFile.toFile(), RealtimeDTO.class);
      latest.setError(null);
    } catch (RuntimeException e) {
      log.warn("저장된 실시간 스냅샷을 읽지 못했습니다: {}", e.getMessage());
    }
  }

  private void saveLatest() {
    try {
      Files.createDirectories(latestFile.getParent());
      jsonMapper.writeValue(latestFile.toFile(), latest);
    } catch (Exception e) {
      log.warn("실시간 스냅샷을 저장하지 못했습니다: {}", e.getMessage());
    }
  }
}
