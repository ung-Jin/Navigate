package com.green.Navigate.weather.service;

import com.green.Navigate.weather.client.KmaClient;
import com.green.Navigate.weather.client.OpenMeteoClient;
import com.green.Navigate.weather.config.WeatherProperties;
import com.green.Navigate.weather.dto.HourlyWeather;
import com.green.Navigate.weather.dto.PortForecast;
import com.green.Navigate.weather.dto.PortWeather;
import com.green.Navigate.weather.port.Port;
import com.green.Navigate.weather.port.PortRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 주기적으로 31개 항구 날씨를 받아 메모리에 캐시. API 요청은 캐시에서 바로 응답 */
@Service("weatherPortWeatherService")
public class PortWeatherService {

    private static final Logger log = LoggerFactory.getLogger(PortWeatherService.class);

    private final PortRepository ports;
    private final OpenMeteoClient openMeteo;
    private final KmaClient kma;
    private final WeatherProperties props;
    /** Bean으로 등록하지 않는 전용 스레드풀 */
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    private final Map<String, PortWeather> cache = new ConcurrentHashMap<>();
    private final Map<String, PortForecast> forecastCache = new ConcurrentHashMap<>();
    private volatile OffsetDateTime lastRefreshedAt;

    public PortWeatherService(PortRepository ports, OpenMeteoClient openMeteo, KmaClient kma,
                              WeatherProperties props) {
        this.ports = ports;
        this.openMeteo = openMeteo;
        this.kma = kma;
        this.props = props;
    }

    /** 앱 시작 직후 1회 + 이후 refresh-ms 간격 */
    @Scheduled(initialDelay = 0, fixedDelayString = "${port-weather.refresh-ms:1800000}")
    public void refreshAll() {
        List<Port> all = ports.findAll();
        List<Future<?>> jobs = new ArrayList<>();
        for (Port p : all) jobs.add(executor.submit(() -> refresh(p)));
        for (Future<?> f : jobs) {
            try { f.get(60, TimeUnit.SECONDS); } catch (Exception e) { log.warn("갱신 대기 실패: {}", e.getMessage()); }
        }
        lastRefreshedAt = OffsetDateTime.now();
        long ok = cache.values().stream().filter(w -> !w.stale()).count();
        log.info("항구 날씨 갱신 완료 {}/{} (source={})", ok, all.size(), props.useKma() ? "kma" : "open-meteo");
    }

    public PortWeather refresh(Port p) {
        PortWeather result;
        try {
            OpenMeteoClient.Result om = props.useKma() ? null : openMeteo.fetchAll(p);
            if (om == null) {
                // 기상청 모드: 현재값은 기상청+Open-Meteo, 예보는 Open-Meteo
                OpenMeteoClient.Result omForKma = safeOpenMeteo(p);
                result = kmaMerged(p, omForKma == null ? null : omForKma.now());
                saveForecast(p, omForKma == null ? null : omForKma.hours());
            } else {
                result = om.now();
                saveForecast(p, om.hours());
            }
        } catch (Exception e) {
            log.warn("{} 조회 실패: {}", p.portName(), e.getMessage());
            result = PortWeather.failed(p, cache.get(p.portId()), e.getMessage());
            markForecastStale(p);
        }
        cache.put(p.portId(), result);
        return result;
    }

    private OpenMeteoClient.Result safeOpenMeteo(Port p) {
        try { return openMeteo.fetchAll(p); } catch (Exception e) { return null; }
    }

    private void saveForecast(Port p, List<HourlyWeather> hours) {
        if (hours == null || hours.isEmpty()) {
            markForecastStale(p);
            return;
        }
        forecastCache.put(p.portId(), new PortForecast(p.portId(), p.portName(), OffsetDateTime.now(), false, hours));
    }

    private void markForecastStale(Port p) {
        PortForecast prev = forecastCache.get(p.portId());
        if (prev != null && !prev.stale()) {
            forecastCache.put(p.portId(), new PortForecast(prev.portId(), prev.portName(), prev.fetchedAt(), true, prev.hours()));
        }
    }

    /** 기상청 실황(기온·바람·강수) + Open-Meteo(시정·파고·수온·돌풍) */
    private PortWeather kmaMerged(Port p, PortWeather om) {
        KmaClient.KmaNow k = kma.fetch(p);
        var v = k.values();
        return PortWeather.of(p, k.observedAt(),
                v.get("T1H"), v.get("RN1"), v.get("WSD"), v.get("VEC"),
                om == null ? null : om.windGustMs(),
                om == null ? null : om.visibilityKm(),
                om == null ? null : om.waveHeightM(),
                om == null ? null : om.wavePeriodS(),
                om == null ? null : om.seaTempC(),
                om == null ? "kma" : "kma+open-meteo");
    }

    /** 향후 72시간 예보 (지난 시각은 잘라서 반환) */
    public Optional<PortForecast> forecast(String idOrName) {
        return ports.find(idOrName).map(p -> {
            PortForecast f = forecastCache.get(p.portId());
            if (f == null) return new PortForecast(p.portId(), p.portName(), null, true, List.of());
            String nowHour = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                    .truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
            List<HourlyWeather> upcoming = f.hours().stream().filter(h -> h.time().compareTo(nowHour) >= 0).toList();
            return new PortForecast(f.portId(), f.portName(), f.fetchedAt(), f.stale(), upcoming);
        });
    }

    public List<PortWeather> findAll(String category) {
        return ports.findAll().stream()
                .filter(p -> category == null || p.category().equals(category))
                .map(p -> cache.getOrDefault(p.portId(), PortWeather.failed(p, null, "아직 조회 전")))
                .toList();
    }

    public Optional<PortWeather> find(String idOrName) {
        return ports.find(idOrName)
                .map(p -> cache.getOrDefault(p.portId(), PortWeather.failed(p, null, "아직 조회 전")));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public OffsetDateTime lastRefreshedAt() {
        return lastRefreshedAt;
    }
}
