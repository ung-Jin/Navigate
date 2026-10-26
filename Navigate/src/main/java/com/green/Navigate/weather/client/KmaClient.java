package com.green.Navigate.weather.client;

import com.green.Navigate.weather.config.WeatherProperties;
import com.green.Navigate.weather.port.Port;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static com.green.Navigate.weather.client.JsonMaps.*;

/**
 * 기상청 단기예보 > 초단기실황 (getUltraSrtNcst)
 * T1H 기온, RN1 1시간 강수, WSD 풍속, VEC 풍향, REH 습도, PTY 강수형태
 * ※ 파고·시정은 없음 → 서비스에서 Open-Meteo 값으로 채움
 */
@Component("weatherKmaClient")
public class KmaClient {

    private static final String URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst"
            + "?serviceKey={key}&pageNo=1&numOfRows=20&dataType=JSON"
            + "&base_date={date}&base_time={time}&nx={nx}&ny={ny}";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RestClient rest = WeatherHttp.CLIENT;
    private final WeatherProperties props;

    public KmaClient(WeatherProperties props) {
        this.props = props;
    }

    public record KmaNow(String observedAt, Map<String, Double> values) {}

    public KmaNow fetch(Port p) {
        // 매시 정각 자료가 약 40분 뒤 제공되므로 40분 전 시각 기준으로 요청
        ZonedDateTime base = ZonedDateTime.now(KST).minusMinutes(40);
        String date = base.format(DateTimeFormatter.BASIC_ISO_DATE);
        String time = base.format(DateTimeFormatter.ofPattern("HH")) + "00";

        Map<?, ?> body = rest.get()
                .uri(URL, props.kmaServiceKey(), date, time, p.kmaNx(), p.kmaNy())
                .retrieve().body(Map.class);

        Map<String, Object> response = obj(body, "response");
        Map<String, Object> header = obj(response, "header");
        if (!"00".equals(str(header, "resultCode"))) {
            throw new IllegalStateException("기상청 API 오류: " + (header == null ? "응답 없음" : str(header, "resultMsg")));
        }

        Map<String, Double> v = new HashMap<>();
        for (Object item : list(obj(obj(response, "body"), "items"), "item")) {
            String cat = str(item, "category");
            Double val = num(item, "obsrValue");
            if (cat != null && val != null) v.put(cat, val);
        }
        String observedAt = "%s-%s-%sT%s:00".formatted(date.substring(0, 4), date.substring(4, 6),
                date.substring(6), time.substring(0, 2));
        return new KmaNow(observedAt, v);
    }
}
