package com.green.Navigate.weather.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "port-weather")
public record WeatherProperties(
        @DefaultValue("open-meteo") String source,
        @DefaultValue("") String kmaServiceKey,
        @DefaultValue("1800000") long refreshMs,
        @DefaultValue("weather/ports.csv") String portsCsv
) {
    public boolean useKma() {
        return "kma".equalsIgnoreCase(source) && kmaServiceKey != null && !kmaServiceKey.isBlank();
    }
}
