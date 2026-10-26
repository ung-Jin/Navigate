package com.green.Navigate.weather.port;

import com.green.Navigate.weather.config.WeatherProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 클래스패스의 ports.csv를 읽어 메모리에 보관 */
@Component("weatherPortRepository")
public class PortRepository {

    private final Map<String, Port> ports = new LinkedHashMap<>();

    public PortRepository(WeatherProperties props) throws IOException {
        var res = new ClassPathResource(props.portsCsv());
        try (var br = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            String header = br.readLine();               // 헤더 건너뜀
            if (header == null) throw new IllegalStateException("ports.csv가 비어 있음");
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",", -1);
                var p = new Port(c[0].replace("﻿", "").trim(), c[1].trim(), c[2].trim(),
                        Double.parseDouble(c[3]), Double.parseDouble(c[4]),
                        Integer.parseInt(c[5]), Integer.parseInt(c[6]), c[7].trim());
                ports.put(p.portId(), p);
            }
        }
    }

    public List<Port> findAll() {
        return List.copyOf(ports.values());
    }

    /**
     * 항구ID(P11) 또는 항구명으로 조회.
     * 이름은 공백·가운뎃점·끝의 '항'을 무시하고 비교 → "부산항", "부산", "평택당진항", "평택·당진항" 모두 찾음
     */
    public Optional<Port> find(String idOrName) {
        if (idOrName == null) return Optional.empty();
        if (ports.containsKey(idOrName)) return Optional.of(ports.get(idOrName));
        String key = normalize(idOrName);
        return ports.values().stream().filter(p -> normalize(p.portName()).equals(key)).findFirst();
    }

    static String normalize(String name) {
        String s = name.replaceAll("[\\s·ㆍ.\\-]", "");
        return s.endsWith("항") ? s.substring(0, s.length() - 1) : s;
    }
}
