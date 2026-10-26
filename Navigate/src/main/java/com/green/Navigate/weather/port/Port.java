package com.green.Navigate.weather.port;

/** 항구 기본 정보 (ports.csv 한 줄) */
public record Port(
        String portId,
        String portName,
        String category,     // 국가관리 / 지방관리
        double lat,
        double lon,
        int kmaNx,           // 기상청 단기예보 격자 X
        int kmaNy,           // 기상청 단기예보 격자 Y
        String coordSource
) {}
