package com.agentflow.shared.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Redacts common credentials from operational tool logs without mutating execution results. */
public final class Redactor {
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern PROVIDER_KEY_MESSAGE = Pattern.compile("(?i)((?:incorrect|invalid)\\s+(?:openai\\s+)?api\\s+key\\s+provided\\s*:\\s*)[^\\s,;.?!\\\"'}]+");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|authorization|credential)(\\s*[:=]\\s*)[\\\"']?[^\\s,;\\\"'}]+[\\\"']?");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    private Redactor() { }

    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                safe.put(name, isSensitiveName(name) ? "[REDACTED]" : sanitize(item));
            });
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>(list.size());
            list.forEach(item -> safe.add(sanitize(item)));
            return safe;
        }
        if (value instanceof String text) return sanitizeText(text);
        return value;
    }

    public static String sanitizeText(String text) {
        String withoutBearer = BEARER.matcher(text).replaceAll("Bearer [REDACTED]");
        String withoutProviderKey = PROVIDER_KEY_MESSAGE.matcher(withoutBearer).replaceAll("$1[REDACTED]");
        return EMAIL.matcher(ASSIGNMENT.matcher(withoutProviderKey).replaceAll("$1$2[REDACTED]")).replaceAll("[REDACTED_EMAIL]");
    }

    private static boolean isSensitiveName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("apikey") || normalized.contains("authorization")
                || normalized.contains("credential") || normalized.equals("auth");
    }
}
