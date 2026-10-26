package com.green.Navigate.weather.controller;

import com.green.Navigate.weather.dto.PortForecast;
import com.green.Navigate.weather.dto.PortWeather;
import com.green.Navigate.weather.port.Port;
import com.green.Navigate.weather.port.PortRepository;
import com.green.Navigate.weather.service.PortWeatherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController("weatherPortWeatherController")
@RequestMapping("/api/weather/ports")
public class PortWeatherController {

    private final PortRepository ports;
    private final PortWeatherService service;

    public PortWeatherController(PortRepository ports, PortWeatherService service) {
        this.ports = ports;
        this.service = service;
    }

    /** 항구 목록 (좌표 포함) */
    @GetMapping
    public List<Port> ports() {
        return ports.findAll();
    }

    /** 전체 항구 현재 날씨. ?category=국가관리 로 필터 가능 */
    @GetMapping("/weather")
    public Map<String, Object> allWeather(@RequestParam(required = false) String category) {
        OffsetDateTime last = service.lastRefreshedAt();
        return Map.of(
                "refreshedAt", last == null ? "아직 조회 전" : last.toString(),
                "ports", service.findAll(category));
    }

    /** 항구 하나: /api/weather/ports/P11/weather 또는 /api/weather/ports/부산항/weather */
    @GetMapping("/{idOrName}/weather")
    public ResponseEntity<PortWeather> oneWeather(@PathVariable String idOrName) {
        return service.find(idOrName).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 향후 72시간 예보: /api/weather/ports/부산항/forecast */
    @GetMapping("/{idOrName}/forecast")
    public ResponseEntity<PortForecast> forecast(@PathVariable String idOrName) {
        return service.forecast(idOrName).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 시연용 수동 갱신 */
    @PostMapping("/weather/refresh")
    public Map<String, Object> refresh() {
        service.refreshAll();
        return Map.of("refreshedAt", String.valueOf(service.lastRefreshedAt()));
    }
}
