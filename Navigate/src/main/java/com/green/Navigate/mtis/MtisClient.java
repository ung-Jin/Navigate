package com.green.Navigate.mtis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


// MTIS 오픈API (한국해양교통안전공단) 호출 + 하루 호출 한도 관리
// 키: .env 의 MTIS_KEY (application.yaml 의 mtis.key)
// 개발키는 하루 500건 → mtis.daily-limit 에서 멈춤. 호출 기록은 cache/mtis-call-log.csv (서버를 다시 켜도 이어서 셈)
@Slf4j
@Component
public class MtisClient {

  public static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final String BASE = "https://mtisopenapi.komsa.or.kr";
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final String key;
  private final int dailyLimit;
  private final Path logFile;
  private final JsonMapper jsonMapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

  private LocalDate countDate;  // callsToday 를 센 날짜
  private int callsToday;       // 오늘 호출 수 (실패한 호출도 한도를 씀)

  public MtisClient(@Value("${mtis.key:}") String key,
                    @Value("${mtis.daily-limit:150}") int dailyLimit,
                    @Value("${mtis.cache-dir:cache}") String cacheDir,
                    JsonMapper jsonMapper) {
    this.key = key.trim();
    this.dailyLimit = dailyLimit;
    this.logFile = Path.of(cacheDir, "mtis-call-log.csv");
    this.jsonMapper = jsonMapper;
    loadTodayCount();
    if (this.key.isEmpty()) {
      log.warn("MTIS_KEY 가 없습니다. Navigate/.env 에 MTIS_KEY=... 를 넣으세요");
    }
  }

  // 오늘 호출 수
  public synchronized int getCallsToday() {
    resetIfNewDay();
    return callsToday;
  }

  public int getDailyLimit() {
    return dailyLimit;
  }

  // path 호출 → 성공하면 응답 JSON (rows() 로 행 목록을 꺼냄)
  // 응답 형식 두 가지를 모두 처리: /api/v1 = {respCode, data:[...]}, /api/v2·/eopt = {response:{header, body}}
  public synchronized JsonNode get(String name, String path, Map<String, String> params) {
    if (key.isEmpty()) {
      throw new MtisException("MTIS 인증키가 없습니다 (.env 의 MTIS_KEY)");
    }
    resetIfNewDay();
    if (callsToday >= dailyLimit) {
      throw new MtisException("오늘 MTIS 호출 " + dailyLimit + "건에 도달해 호출을 멈췄습니다");
    }

    StringBuilder url = new StringBuilder(BASE).append(path).append("?serviceKey=").append(enc(key));
    params.forEach((k, v) -> url.append('&').append(enc(k)).append('=').append(enc(v)));

    JsonNode root;
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
          .timeout(Duration.ofSeconds(60))
          .header("User-Agent", "Navigate/0.1")
          .GET().build();
      String text = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
      root = jsonMapper.readTree(text);
    } catch (IOException | InterruptedException | RuntimeException e) {
      writeLog(name, "NETWORK");
      throw new MtisException(name + " 네트워크 오류: " + e.getMessage());
    }

    if (root.has("response")) {  // /api/v2, /eopt 형식
      JsonNode header = root.path("response").path("header");
      String code = header.path("resultCode").asString("");
      writeLog(name, code);
      if (!"200".equals(code)) {
        throw new MtisException(name + " 응답 " + code + " " + header.path("resultMsg").asString(""), code);
      }
      return root.path("response").path("body");
    }
    String code = root.path("respCode").asString("");  // /api/v1 형식
    writeLog(name, code);
    if (!"200".equals(code)) {
      throw new MtisException(name + " 응답 " + code + " " + root.path("errorMsg").asString(""), code);
    }
    return root;
  }

  // get() 결과에서 행 목록 꺼내기 (v1 = data, v2·eopt = items.item — 1건이면 배열이 아니라 객체로 옴)
  public static List<JsonNode> rows(JsonNode res) {
    JsonNode arr = res.has("data") ? res.path("data") : res.path("items").path("item");
    List<JsonNode> list = new ArrayList<>();
    if (arr.isArray()) {
      arr.forEach(list::add);
    } else if (arr.isObject()) {
      list.add(arr);
    }
    return list;
  }

  // 행에서 숫자 꺼내기 (숫자·글자 둘 다 받음, 없거나 이상하면 null)
  public static Double num(JsonNode row, String field) {
    JsonNode v = row.path(field);
    if (v.isNumber()) {
      return v.asDouble();
    }
    try {
      return v.isString() && !v.asString().isBlank() ? Double.parseDouble(v.asString()) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private void resetIfNewDay() {
    LocalDate today = LocalDate.now(KST);
    if (!today.equals(countDate)) {
      countDate = today;
      callsToday = 0;
    }
  }

  // 서버를 켤 때 기록 파일에서 오늘 호출 수를 읽어 이어서 셈
  private void loadTodayCount() {
    countDate = LocalDate.now(KST);
    callsToday = 0;
    if (!Files.exists(logFile)) {
      return;
    }
    String today = countDate.toString();
    try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
      callsToday = (int) lines.filter(line -> line.startsWith(today)).count();
    } catch (IOException e) {
      log.warn("MTIS 호출 기록을 읽지 못했습니다: {}", e.getMessage());
    }
  }

  private void writeLog(String name, String code) {
    callsToday++;
    try {
      Files.createDirectories(logFile.getParent());
      boolean isNew = !Files.exists(logFile);
      String line = (isNew ? "called_at,api,result_code\n" : "")
          + LocalDateTime.now(KST).format(TIME) + "," + name + "," + code + "\n";
      Files.writeString(logFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.warn("MTIS 호출 기록을 쓰지 못했습니다: {}", e.getMessage());
    }
  }
}
