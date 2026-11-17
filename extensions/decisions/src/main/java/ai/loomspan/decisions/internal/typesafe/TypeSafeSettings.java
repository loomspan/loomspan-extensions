package ai.loomspan.decisions.internal.typesafe;

import java.net.URI;
import java.util.Map;

/** Immutable invocation-local settings; no credential or environment resolver. */
record TypeSafeSettings(URI endpoint, String apiKey) {
    static TypeSafeSettings capture(Map<String, Object> skill, Map<String, Object> executor) {
        String key = selected(skill, executor, "api_key", null);
        String base = selected(skill, executor, "base_url", "https://api.typesafe.ai");
        String keyPath = path(skill, "api_key");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 32 || c > 126) throw invalid(keyPath, "must contain only HTTP header characters");
        }
        String basePath = path(skill, "base_url");
        try {
            URI uri = new URI(base).parseServerAuthority();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() < -1
                    || uri.getRawAuthority().endsWith(":")) {
                throw invalid(basePath, "must be an absolute HTTP(S) URL with a host and optional path only");
            }
            int end = base.length();
            while (end > 0 && base.charAt(end - 1) == '/') end--;
            return new TypeSafeSettings(URI.create(base.substring(0, end) + "/v1/systemone"), key);
        } catch (java.net.URISyntaxException ex) {
            throw invalid(basePath, "must be a valid HTTP(S) URL");
        }
    }

    private static String selected(Map<String, Object> skill, Map<String, Object> executor,
                                   String name, String fallback) {
        Object value = skill.containsKey(name) ? skill.get(name) : executor.get(name);
        if (!skill.containsKey(name) && !executor.containsKey(name) && fallback != null) return fallback;
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(path(skill, name), "must be a nonblank string");
        }
        return text;
    }

    private static String path(Map<String, Object> skill, String name) {
        return (skill.containsKey(name) ? "skillConfig." : "executorConfig.") + name;
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException(path + " " + message);
    }
}
