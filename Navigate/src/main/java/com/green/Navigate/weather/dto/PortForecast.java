package com.green.Navigate.weather.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 항구 하나의 향후 72시간 예보 */
public record PortForecast(
        String portId,
        String portName,
        OffsetDateTime fetchedAt,
        boolean stale,
        List<HourlyWeather> hours
) {}
