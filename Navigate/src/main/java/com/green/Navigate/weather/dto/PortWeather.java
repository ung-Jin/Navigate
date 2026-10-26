package com.green.Navigate.weather.dto;

import com.green.Navigate.weather.port.Port;

import java.time.OffsetDateTime;

/** 항구 하나의 현재 날씨 (API 응답 형식) */
public record PortWeather(
        String portId,
        String portName,
        String category,
        double lat,
        double lon,
        String observedAt,      // 관측(실황) 시각
        Double tempC,
        Double precipMm,
        Double windSpeedMs,
        Double windDirDeg,
        Double windGustMs,
        Double visibilityKm,
        Double waveHeightM,
        Double wavePeriodS,
        Double seaTempC,
        String level,           // 안전 / 주의 / 위험 / 미상
        String source,
        OffsetDateTime fetchedAt,
        boolean stale,          // true면 이번 갱신 실패 → 이전 값 유지 중
        String error
) {
    /** 지도 색칠용 간이 등급. 3m·14m/s·1km는 풍랑주의보/여객선 통제 기준, 나머지는 프로젝트 가정값 */
    public static String level(Double wave, Double wind, Double vis) {
        if (wave == null && wind == null && vis == null) return "미상";
        if (ge(wave, 3.0) || ge(wind, 14.0) || le(vis, 1.0)) return "위험";
        if (ge(wave, 2.0) || ge(wind, 10.0) || le(vis, 3.0)) return "주의";
        return "안전";
    }

    private static boolean ge(Double v, double t) { return v != null && v >= t; }
    private static boolean le(Double v, double t) { return v != null && v <= t; }

    public static PortWeather of(Port p, String observedAt, Double tempC, Double precipMm,
                                 Double windSpeedMs, Double windDirDeg, Double windGustMs,
                                 Double visibilityKm, Double waveHeightM, Double wavePeriodS,
                                 Double seaTempC, String source) {
        return new PortWeather(p.portId(), p.portName(), p.category(), p.lat(), p.lon(),
                observedAt, tempC, precipMm, windSpeedMs, windDirDeg, windGustMs,
                visibilityKm, waveHeightM, wavePeriodS, seaTempC,
                level(waveHeightM, windSpeedMs, visibilityKm),
                source, OffsetDateTime.now(), false, null);
    }

    /** 갱신 실패 시: 이전 값이 있으면 stale 표시해서 유지, 없으면 빈 값 + 에러 */
    public static PortWeather failed(Port p, PortWeather previous, String error) {
        if (previous != null) {
            return new PortWeather(previous.portId(), previous.portName(), previous.category(),
                    previous.lat(), previous.lon(), previous.observedAt(), previous.tempC(),
                    previous.precipMm(), previous.windSpeedMs(), previous.windDirDeg(),
                    previous.windGustMs(), previous.visibilityKm(), previous.waveHeightM(),
                    previous.wavePeriodS(), previous.seaTempC(), previous.level(),
                    previous.source(), previous.fetchedAt(), true, error);
        }
        return new PortWeather(p.portId(), p.portName(), p.category(), p.lat(), p.lon(),
                null, null, null, null, null, null, null, null, null, null,
                "미상", null, OffsetDateTime.now(), true, error);
    }
}
