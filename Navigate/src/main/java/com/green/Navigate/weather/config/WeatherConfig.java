package com.green.Navigate.weather.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * weather 패키지 설정.
 * RestClient·스레드풀은 다른 팀원 Bean과 타입이 겹치지 않도록 Bean으로 등록하지 않고 내부에서 생성함.
 * 설정값 기본은 WeatherProperties의 @DefaultValue → application.yaml 수정 불필요.
 */
@Configuration("weatherConfig")
@EnableScheduling
@EnableConfigurationProperties(WeatherProperties.class)
public class WeatherConfig {
}
