package com.green.Navigate.weather.client;

import com.green.Navigate.weather.dto.HourlyWeather;
import com.green.Navigate.weather.dto.PortWeather;
import com.green.Navigate.weather.port.Port;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.green.Navigate.weather.client.JsonMaps.*;

/** Open-Meteo: 키 없이 사용 (비상업 무료). 현재값 + 향후 72시간 예보를 한 번의 호출로 받음 */
@Component("weatherOpenMeteoClient")
public class OpenMeteoClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoClient.class);

    public static final int FORECAST_HOURS = 72;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude={lat}&longitude={lon}&current={cur}&hourly={hourly}"
            + "&forecast_days=4&wind_speed_unit=ms&timezone=Asia/Seoul";
    private static final String FORECAST_VARS =
            "temperature_2m,precipitation,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility";

    private static final String MARINE_URL = "https://marine-api.open-meteo.com/v1/marine"
            + "?latitude={lat}&longitude={lon}&current={cur}&hourly={hourly}"
            + "&forecast_days=4&timezone=Asia/Seoul";
    private static final String MARINE_CURRENT = "wave_height,wave_period,sea_surface_temperature";
    private static final String MARINE_HOURLY = "wave_height,wave_period";

    private final RestClient rest = WeatherHttp.CLIENT;

    /** 현재값 + 72시간 예보 */
    public record Result(PortWeather now, List<HourlyWeather> hours) {}

    public Result fetchAll(Port p) {
        Map<?, ?> body = rest.get().uri(FORECAST_URL, p.lat(), p.lon(), FORECAST_VARS, FORECAST_VARS)
                .retrieve().body(Map.class);
        Map<?, ?> marine = fetchMarine(p);

        Map<String, Object> cur = obj(body, "current");
        Map<String, Object> mCur = obj(marine, "current");
        PortWeather now = PortWeather.of(p,
                str(cur, "time"),
                num(cur, "temperature_2m"),
                num(cur, "precipitation"),
                num(cur, "wind_speed_10m"),
                num(cur, "wind_direction_10m"),
                num(cur, "wind_gusts_10m"),
                toKm(num(cur, "visibility")),
                num(mCur, "wave_height"),
                num(mCur, "wave_period"),
                num(mCur, "sea_surface_temperature"),
                "open-meteo");

        return new Result(now, hourly(obj(body, "hourly"), obj(marine, "hourly")));
    }

    /** 현재값만 필요할 때 (기상청 모드 보완용) */
    public PortWeather fetch(Port p) {
        return fetchAll(p).now();
    }

    /** 지금 시각(정시)부터 72시간만 잘라서 날씨+파랑을 시간 기준으로 합침 */
    List<HourlyWeather> hourly(Map<String, Object> w, Map<String, Object> m) {
        List<?> times = list(w, "time");
        if (times.isEmpty()) return List.of();

        Map<String, Integer> marineIdx = new HashMap<>();
        List<?> mTimes = list(m, "time");
        for (int i = 0; i < mTimes.size(); i++) marineIdx.put(String.valueOf(mTimes.get(i)), i);

        LocalDateTime from = LocalDateTime.now(KST).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime to = from.plusHours(FORECAST_HOURS);

        List<HourlyWeather> out = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            String t = String.valueOf(times.get(i));
            LocalDateTime lt;
            try { lt = LocalDateTime.parse(t); } catch (Exception e) { continue; }
            if (lt.isBefore(from) || !lt.isBefore(to)) continue;

            Integer mi = marineIdx.get(t);
            Double wave = mi == null ? null : at(m, "wave_height", mi);
            Double wind = at(w, "wind_speed_10m", i);
            Double vis = toKm(at(w, "visibility", i));
            out.add(new HourlyWeather(t,
                    wind,
                    at(w, "wind_direction_10m", i),
                    at(w, "wind_gusts_10m", i),
                    vis,
                    wave,
                    mi == null ? null : at(m, "wave_period", mi),
                    at(w, "temperature_2m", i),
                    at(w, "precipitation", i),
                    PortWeather.level(wave, wind, vis)));
        }
        return out;
    }

    private static Double toKm(Double meters) {
        return meters == null ? null : Math.round(meters / 10.0) / 100.0;
    }

    /** 해상 격자가 없는 위치(서울항 등)는 실패하거나 null → 날씨는 그대로 반환 */
    Map<?, ?> fetchMarine(Port p) {
        try {
            return rest.get().uri(MARINE_URL, p.lat(), p.lon(), MARINE_CURRENT, MARINE_HOURLY)
                    .retrieve().body(Map.class);
        } catch (Exception e) {
            log.debug("marine 조회 실패 {}: {}", p.portName(), e.getMessage());
            return null;
        }
    }
}
