package com.green.Navigate.weather.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** weather 전용 HTTP 클라이언트 (Bean 아님) */
final class WeatherHttp {
    private WeatherHttp() {}

    static final RestClient CLIENT = create();

    private static RestClient create() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }
}
