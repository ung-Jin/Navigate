package com.green.Navigate.safety.service;

import com.green.Navigate.main.service.NearPortService;
import com.green.Navigate.mtis.MtisClient;
import com.green.Navigate.mtis.MtisException;
import com.green.Navigate.mtis.MtisGrid;
import com.green.Navigate.safety.dto.AccidentGridDTO;
import com.green.Navigate.safety.dto.HotspotDTO;
import com.green.Navigate.safety.dto.HotspotPointDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 사고 다발 자료 (MTIS 연도별) — 사고다발 격자 #243 + 좌초·좌주 다발지점 #231
// 1년에 한 번 갱신되는 자료라 한 번 받으면 파일(cache/hotspot/연도.json)에 두고 계속 씀 → MTIS 는 연도마다 2건만 부름
// 가장 최근 연도(작년)부터 찾고, 아직 발표 전이면 그 전 해로 내려감
@Slf4j
@Service
public class HotspotService {

  private static final int LOOK_BACK = 3;  // 작년부터 몇 해까지 내려가 볼지

  private final MtisClient mtisClient;
  private final NearPortService nearPortService;
  private final JsonMapper jsonMapper;
  private final Path dir;
  private final Map<Integer, HotspotDTO> memory = new ConcurrentHashMap<>();
  private final Map<Integer, LocalDate> notPublished = new ConcurrentHashMap<>();  // 발표 전으로 확인한 연도 → 확인한 날 (그날은 다시 묻지 않음)

  public HotspotService(MtisClient mtisClient, NearPortService nearPortService, JsonMapper jsonMapper,
                        @Value("${mtis.cache-dir:cache}") String cacheDir) {
    this.mtisClient = mtisClient;
    this.nearPortService = nearPortService;
    this.jsonMapper = jsonMapper;
    this.dir = Path.of(cacheDir, "hotspot");
  }

  // 가장 최근 연도의 사고 다발 자료
  public HotspotDTO getLatest() {
    int lastYear = LocalDate.now(MtisClient.KST).getYear() - 1;
    for (int y = lastYear; y > lastYear - LOOK_BACK; y--) {
      HotspotDTO d;
      try {
        d = load(y);
      } catch (MtisException e) {
        log.warn("사고 다발 자료 받기 실패 ({}년): {}", y, e.getMessage());
        return failed(y, e.getMessage());
      }
      if (d != null) {
        return d;
      }
    }
    return failed(lastYear, "최근 " + LOOK_BACK + "년 사고 다발 자료가 아직 없습니다");
  }

  // 메모리 → 파일 → MTIS 순서. 아직 발표 전이면 null
  private HotspotDTO load(int year) {
    HotspotDTO d = memory.get(year);
    if (d != null) {
      return d;
    }
    Path file = dir.resolve(year + ".json");
    if (Files.exists(file)) {
      try {
        d = jsonMapper.readValue(file.toFile(), HotspotDTO.class);
        memory.put(year, d);
        return d;
      } catch (RuntimeException e) {
        log.warn("저장된 사고 다발 자료를 읽지 못했습니다 ({}): {}", file, e.getMessage());
      }
    }
    LocalDate today = LocalDate.now(MtisClient.KST);
    if (today.equals(notPublished.get(year))) {
      return null;
    }

    d = fetch(year);
    if (d == null) {
      notPublished.put(year, today);
      return null;
    }
    memory.put(year, d);
    try {
      Files.createDirectories(dir);
      jsonMapper.writeValue(file.toFile(), d);
    } catch (Exception e) {
      log.warn("사고 다발 자료를 저장하지 못했습니다 ({}): {}", file, e.getMessage());
    }
    return d;
  }

  private HotspotDTO fetch(int year) {
    String y = String.valueOf(year);
    List<JsonNode> gridRows = rowsOrEmpty("243_acdnt_fq", "/api/v2/grid/acdnt_fq",
        Map.of("reqYear", y, "numOfRows", "5000", "pageNo", "1"));
    if (gridRows.isEmpty()) {
      return null;  // 그해 자료가 아직 없음
    }
    List<JsonNode> pointRows = rowsOrEmpty("231_stranding", "/api/v2/grid/acdnt_stgr_grn",
        Map.of("reqYear", y, "numOfRows", "1000", "pageNo", "1"));

    List<AccidentGridDTO> grids = new ArrayList<>();
    int total = 0;
    for (JsonNode r : gridRows) {
      double[] ll = MtisGrid.toLatLng("GR4_" + r.path("grid_id").asString(""));  // grid_id 에 GR4_ 접두어가 없음
      if (ll == null) {
        continue;
      }
      total++;
      if (!"O".equals(r.path("acdnt_fq_grid_yn").asString(""))) {
        continue;  // MTIS 가 다발 격자로 지정한 칸만 (O/X)
      }
      AccidentGridDTO g = new AccidentGridDTO();
      g.setId("GR4_" + r.path("grid_id").asString(""));
      g.setLat(ll[0]);
      g.setLng(ll[1]);
      Double count = MtisClient.num(r, "acdnt_co");
      g.setCount(count == null ? null : count.intValue());
      g.setAvg(MtisClient.num(r, "avg_acdnt_co"));
      g.setNear(nearPortService.describe(ll[0], ll[1]));
      grids.add(g);
    }
    grids.sort(Comparator.comparing(AccidentGridDTO::getCount, Comparator.nullsFirst(Comparator.naturalOrder())));

    List<HotspotPointDTO> points = new ArrayList<>();
    for (JsonNode r : pointRows) {
      Double lat = MtisClient.num(r, "lat");
      Double lng = MtisClient.num(r, "long");
      if (lat == null || lng == null) {
        continue;
      }
      HotspotPointDTO p = new HotspotPointDTO();
      p.setLat(lat);
      p.setLng(lng);
      Double size = MtisClient.num(r, "cluster_size");
      p.setSize(size == null ? null : size.intValue());
      p.setNear(nearPortService.describe(lat, lng));
      points.add(p);
    }

    HotspotDTO dto = new HotspotDTO();
    dto.setYear(year);
    dto.setGridTotal(total);
    dto.setGrids(grids);
    dto.setStranding(points);
    log.info("사고 다발 자료 {}년: 다발 격자 {}칸 (전체 {}칸) · 좌초 다발지점 {}곳 (오늘 MTIS {}건)",
        year, grids.size(), total, points.size(), mtisClient.getCallsToday());
    return dto;
  }

  // 자료 없음(153)은 빈 목록으로, 그 밖의 오류(한도·네트워크)는 그대로 올려 보냄
  private List<JsonNode> rowsOrEmpty(String name, String path, Map<String, String> params) {
    try {
      return MtisClient.rows(mtisClient.get(name, path, params));
    } catch (MtisException e) {
      if ("153".equals(e.getCode())) {
        return List.of();
      }
      throw e;
    }
  }

  private static HotspotDTO failed(int year, String message) {
    HotspotDTO dto = new HotspotDTO();
    dto.setYear(year);
    dto.setError(message);
    return dto;
  }
}
