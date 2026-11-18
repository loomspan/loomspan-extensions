package ai.loomspan.decisions.internal.typesafe;

import java.net.URI;
import java.util.Map;

/** Immutable invocation-local settings; no credential or environment resolver. */
record TypeSafeSettings(URI endpoint, String apiKey) {
    static TypeSafeSettings capture(Map<String, Object> executor) {
        String key = setting(executor, "api_key", null);
        String base = setting(executor, "base_url", "https://api.typesafe.ai");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 32 || c > 126) throw invalid("executorConfig.api_key", "must contain only HTTP header characters");
        }
        try {
            URI uri = new URI(base).parseServerAuthority();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() < -1
                    || uri.getRawAuthority().endsWith(":")) {
                throw invalid("executorConfig.base_url", "must be an absolute HTTP(S) URL with a host and optional path only");
            }
            int end = base.length();
            while (end > 0 && base.charAt(end - 1) == '/') end--;
            return new TypeSafeSettings(URI.create(base.substring(0, end) + "/v1/systemone"), key);
        } catch (java.net.URISyntaxException ex) {
            throw invalid("executorConfig.base_url", "must be a valid HTTP(S) URL");
        }
    }

    private static String setting(Map<String, Object> executor, String name, String fallback) {
        Object value = executor.getOrDefault(name, fallback);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid("executorConfig." + name, "must be a nonblank string");
        }
        return text;
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException(path + " " + message);
    }
}
