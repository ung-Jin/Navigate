package com.green.Navigate.weather.client;

import java.util.List;
import java.util.Map;

/** JSON 응답을 Map으로 받아 꺼내는 도우미 (Jackson 버전과 무관하게 동작) */
final class JsonMaps {
    private JsonMaps() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object node, String key) {
        if (node instanceof Map<?, ?> m && m.get(key) instanceof Map<?, ?> child) {
            return (Map<String, Object>) child;
        }
        return null;
    }

    static List<?> list(Object node, String key) {
        if (node instanceof Map<?, ?> m && m.get(key) instanceof List<?> l) return l;
        return List.of();
    }

    static Double num(Object node, String key) {
        if (!(node instanceof Map<?, ?> m)) return null;
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /** hourly 배열의 i번째 숫자 */
    static Double at(Object node, String key, int i) {
        List<?> l = list(node, key);
        if (i < 0 || i >= l.size()) return null;
        Object v = l.get(i);
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    static String str(Object node, String key) {
        if (!(node instanceof Map<?, ?> m)) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
