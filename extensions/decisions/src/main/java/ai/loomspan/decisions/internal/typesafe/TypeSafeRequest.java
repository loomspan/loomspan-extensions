package ai.loomspan.decisions.internal.typesafe;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.*;
import tools.jackson.databind.json.JsonMapper;

/** Native request construction, before any transport retry. Not an application API. */
final class TypeSafeRequest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Object MISSING = new Object();

    static byte[] prepare(Map<String, Object> skill, Map<String, Object> executor, Map<String, Object> input) {
        fields(skill, Set.of("request", "bindings", "api_key", "base_url"), "skillConfig");
        fields(executor, Set.of("api_key", "base_url"), "executorConfig");
        var request = object(copy(skill.get("request"), "skillConfig.request", new IdentityHashMap<>(), 0), "skillConfig.request");
        if (skill.containsKey("bindings")) {
            var bindings = object(skill.get("bindings"), "skillConfig.bindings");
            List<List<String>> targets = new ArrayList<>();
            for (var entry : bindings.entrySet()) {
                String path = "skillConfig.bindings[" + entry.getKey() + "]";
                var target = pointer(entry.getKey(), path);
                require(!target.isEmpty(), path, "request root replacement is unsupported");
                for (var prior : targets)
                    require(!prefix(target, prior) && !prefix(prior, target), path, "overlapping target pointers");
                targets.add(target);
                require(entry.getValue() instanceof String, path, "source must be a JSON Pointer string");
                Object value = lookup(input, pointer((String) entry.getValue(), path));
                bind(request, target, value == MISSING ? MISSING : copy(value, path, new IdentityHashMap<>(), 0), path);
            }
        }
        validate(request);
        byte[] result = JSON.writeValueAsBytes(request);
        require(result.length <= 32 * 1024 * 1024, "skillConfig.request", "exceeds 32 MiB JSON body");
        return result;
    }

    private static void validate(Map<String, Object> request) {
        String root = "skillConfig.request";
        fields(request, Set.of("model", "state", "questions"), root);
        require(request.get("model") instanceof String model && !model.isBlank(), root + ".model", "must be nonblank text");
        require(request.containsKey("state"), root + ".state", "is required");
        description(request.get("state"), false, root + ".state");
        var questions = object(request.get("questions"), root + ".questions");
        require(!questions.isEmpty(), root + ".questions", "must not be empty");
        for (var entry : questions.entrySet()) {
            String path = root + ".questions." + entry.getKey();
            require(!entry.getKey().isBlank(), path, "question name must not be blank");
            var question = object(entry.getValue(), path);
            fields(question, Set.of("type", "instructions", "criteria"), path);
            Object type = question.get("type");
            require(type instanceof String && Set.of("choice", "noul", "score").contains(type), path + ".type", "supports only choice, noul, score (images/Box unsupported)");
            if (question.containsKey("instructions")) description(question.get("instructions"), true, path + ".instructions");
            Object criteria = question.get("criteria");
            switch ((String) type) {
                case "choice" -> {
                    var choices = object(criteria, path + ".criteria");
                    require(!choices.isEmpty() && choices.size() <= 255, path + ".criteria", "requires 1 to 255 choices");
                    choices.forEach((name, value) -> {
                        require(!name.isBlank(), path + ".criteria", "choice names must not be blank");
                        description(value, true, path + ".criteria." + name);
                    });
                }
                case "score" -> {
                    require(criteria instanceof List<?> values && !values.isEmpty() && values.size() <= 10, path + ".criteria", "requires 1 to 10 ordered levels");
                    var levels = (List<?>) criteria;
                    for (int i = 0; i < levels.size(); i++) description(levels.get(i), false, path + ".criteria[" + i + "]");
                }
                case "noul" -> {
                    if (criteria != null) {
                        var binary = object(criteria, path + ".criteria");
                        fields(binary, Set.of("true", "false"), path + ".criteria");
                        binary.forEach((key, value) -> description(value, true, path + ".criteria." + key));
                    }
                    require(content(question.get("instructions")) || content(criteria), path, "noul requires descriptive instructions or criteria");
                }
                default -> throw new AssertionError(type);
            }
        }
    }

    private static boolean content(Object value) {
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(TypeSafeRequest::content);
        if (value instanceof List<?> list) return list.stream().anyMatch(TypeSafeRequest::content);
        return value != null;
    }

    private static void description(Object value, boolean nullable, String path) {
        require((nullable && value == null) || value instanceof String || value instanceof Map<?, ?> || value instanceof List<?>, path, "must be native text, object or array" + (nullable ? " or null" : ""));
    }

    private static Object copy(Object value, String path, IdentityHashMap<Object, Boolean> parents, int depth) {
        require(depth <= 128, path, "nesting exceeds 128");
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Double d) { require(Double.isFinite(d), path, "must be finite"); return value; }
        if (value instanceof Float f) { require(Float.isFinite(f), path, "must be finite"); return value; }
        if (value instanceof Resource resource) {
            require(resource instanceof ByteArrayResource || resource instanceof ClassPathResource || resource instanceof FileSystemResource || resource instanceof PathResource,
                    path, "unsupported Resource; use local byte-array, classpath, filesystem or path content");
            try (var stream = resource.getInputStream()) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException ex) { throw new IllegalArgumentException(path + ": cannot read local content", ex); }
        }
        require(parents.put(value, true) == null, path, "cyclic JSON value");
        try {
            if (value instanceof Map<?, ?> map) {
                var result = new LinkedHashMap<String, Object>();
                for (var entry : map.entrySet()) {
                    require(entry.getKey() instanceof String, path, "object keys must be strings");
                    String key = (String) entry.getKey();
                    result.put(key, copy(entry.getValue(), path + "." + key, parents, depth + 1));
                }
                return result;
            }
            if (value instanceof List<?> list) {
                var result = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) result.add(copy(list.get(i), path + "[" + i + "]", parents, depth + 1));
                return result;
            }
            throw new IllegalArgumentException(path + ": unsupported JSON value " + value.getClass().getName());
        } finally { parents.remove(value); }
    }

    private static List<String> pointer(String value, String path) {
        if (value.isEmpty()) return List.of();
        require(value.startsWith("/"), path, "must be an RFC 6901 JSON Pointer");
        var result = new ArrayList<String>();
        for (String part : value.substring(1).split("/", -1)) {
            for (int i = 0; i < part.length(); i++) if (part.charAt(i) == '~') {
                require(i + 1 < part.length() && (part.charAt(i + 1) == '0' || part.charAt(i + 1) == '1'), path, "invalid JSON Pointer escape");
                i++;
            }
            result.add(part.replace("~1", "/").replace("~0", "~"));
        }
        return result;
    }

    private static boolean prefix(List<String> first, List<String> second) {
        return first.size() <= second.size() && second.subList(0, first.size()).equals(first);
    }

    private static Object lookup(Object current, List<String> parts) {
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) current = map.containsKey(part) ? map.get(part) : MISSING;
            else if (current instanceof List<?> list && part.matches("0|[1-9][0-9]*")) {
                try { int index = Integer.parseInt(part); current = index < list.size() ? list.get(index) : MISSING; }
                catch (NumberFormatException ex) { return MISSING; }
            } else return MISSING;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void bind(Map<String, Object> request, List<String> parts, Object value, String path) {
        Object parent = lookup(request, parts.subList(0, parts.size() - 1));
        String key = parts.getLast();
        if (parent instanceof Map<?, ?> map) {
            var target = (Map<String, Object>) map;
            if (value == MISSING) target.remove(key); else target.put(key, value);
        } else if (parent instanceof List<?> list) {
            require(value != MISSING, path, "cannot omit array element");
            require(key.matches("0|[1-9][0-9]*"), path, "invalid array index");
            int index;
            try { index = Integer.parseInt(key); } catch (NumberFormatException ex) { throw new IllegalArgumentException(path + ": invalid array index"); }
            require(index < list.size(), path, "array index out of range");
            ((List<Object>) list).set(index, value);
        } else throw new IllegalArgumentException(path + ": target parent is missing or scalar");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String path) {
        require(value instanceof Map<?, ?>, path, "must be an object");
        for (Object key : ((Map<?, ?>) value).keySet()) require(key instanceof String, path, "object keys must be strings");
        return (Map<String, Object>) value;
    }

    private static void fields(Map<String, Object> value, Set<String> allowed, String path) {
        for (String key : value.keySet()) require(allowed.contains(key), path + "." + key, "unsupported field/capability");
    }

    private static void require(boolean condition, String path, String message) {
        if (!condition) throw new IllegalArgumentException(path + ": " + message);
    }
}
