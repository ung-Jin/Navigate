package com.green.Navigate.weather.dto;

/** 1시간 단위 예보 한 칸 */
public record HourlyWeather(
        String time,            // 2026-10-21T15:00 (KST)
        Double windSpeedMs,
        Double windDirDeg,
        Double windGustMs,
        Double visibilityKm,
        Double waveHeightM,
        Double wavePeriodS,
        Double tempC,
        Double precipMm,
        String level            // 안전 / 주의 / 위험 / 미상
) {}
