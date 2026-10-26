package com.green.Navigate.mtis;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

// MTIS 예보 자료 공통 저장 (사고위험 예보 #261, 혼잡 예보 #259)
// - 예보는 매일 새로 나오므로 "오늘 받은 것"만 씀: 메모리 → 파일(cache/…/키__받은날.json) → MTIS 순서
// - 아직 발표 안 된 시각(153)은 1시간 동안 다시 묻지 않음 — 실패한 호출도 하루 한도를 씀
@Slf4j
public class ForecastCache<T> {

  public static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
  public static final String NOT_READY = "이 시각 예보는 아직 발표되지 않았습니다";
  private static final long NOT_READY_WAIT_MS = 60 * 60 * 1000;

  private final Path dir;
  private final Class<T> type;
  private final JsonMapper jsonMapper;
  private final Map<String, T> memory = new ConcurrentHashMap<>();
  private final Map<String, Long> notReady = new ConcurrentHashMap<>();  // 예보 키 → 153 받은 시각
  private String memoryDay;                                               // memory 가 담고 있는 날 (바뀌면 비움)

  public ForecastCache(Path dir, Class<T> type, JsonMapper jsonMapper) {
    this.dir = dir;
    this.type = type;
    this.jsonMapper = jsonMapper;
  }

  public static String today() {
    return LocalDate.now(MtisClient.KST).format(YMD);
  }

  // 예보 날짜 확인: yyyyMMdd, 오늘~모레만. 맞으면 null, 아니면 안내 문구
  public static String checkDate(String date) {
    if (date == null || !date.matches("\\d{8}")) {
      return "날짜 형식이 맞지 않습니다 (yyyyMMdd)";
    }
    LocalDate d = LocalDate.parse(date, YMD);
    LocalDate t = LocalDate.now(MtisClient.KST);
    if (d.isBefore(t) || d.isAfter(t.plusDays(2))) {
      return "예보는 오늘부터 모레까지만 있습니다";
    }
    return null;
  }

  // key = 예보 날짜_시각. fetch = MTIS 에서 받아 만들기, failed = 안내 문구 → 실패 응답 만들기
  public synchronized T get(String key, Supplier<T> fetch, Function<String, T> failed) {
    String today = today();
    if (!today.equals(memoryDay)) {
      memory.clear();
      memoryDay = today;
    }
    T value = memory.get(key);
    if (value != null) {
      return value;
    }
    Path file = dir.resolve(key + "__" + today + ".json");
    if (Files.exists(file)) {
      try {
        value = jsonMapper.readValue(file.toFile(), type);
        memory.put(key, value);
        return value;
      } catch (RuntimeException e) {
        log.warn("저장된 예보를 읽지 못했습니다 ({}): {}", file, e.getMessage());
      }
    }
    Long t = notReady.get(key);
    if (t != null && System.currentTimeMillis() - t < NOT_READY_WAIT_MS) {
      return failed.apply(NOT_READY);
    }

    try {
      value = fetch.get();
    } catch (MtisException e) {
      if ("153".equals(e.getCode())) {
        notReady.put(key, System.currentTimeMillis());
        return failed.apply(NOT_READY);
      }
      log.warn("예보 받기 실패 ({}): {}", key, e.getMessage());
      return failed.apply(e.getMessage());
    }
    memory.put(key, value);
    try {
      Files.createDirectories(dir);
      jsonMapper.writeValue(file.toFile(), value);
    } catch (Exception e) {
      log.warn("예보를 저장하지 못했습니다 ({}): {}", file, e.getMessage());
    }
    return value;
  }
}
