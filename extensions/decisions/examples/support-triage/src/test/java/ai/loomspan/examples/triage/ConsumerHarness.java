package ai.loomspan.examples.triage;

import ai.loomspan.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Reusable real-application seam for additional consumer qualification scenarios. */
final class ConsumerHarness implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    final MockWebServer provider = new MockWebServer();
    final MockWebServer planner = new MockWebServer();
    final ConfigurableApplicationContext context;
    final SkillTemplate skills;
    final SkillReloader reloader;
    final List<SkillExecutionView> views = new ArrayList<>();

    ConsumerHarness() throws Exception {
        Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
        provider.start(); planner.start();
        Path startup = Files.createTempFile(Path.of(System.getProperty("java.io.tmpdir")), "startup-", ".yml");
        Files.writeString(startup, "loomspan:\n  executor-config:\n    default:\n      typesafe: " + JSON.writeValueAsString(settings()) + "\n");
        context = new SpringApplicationBuilder(TriageApplication.class).web(WebApplicationType.NONE).properties(
                "spring.main.banner-mode=off", "logging.level.root=WARN",
                "loomspan.skills.locations=classpath:/empty/*.yaml",
                "spring.config.location=" + startup.toUri(),
                "loomspan.connections.local.driver=openai", "loomspan.connections.local.base-url=" + planner.url("/v1"),
                "loomspan.connections.local.api-key=planner-test-key", "loomspan.connections.local.provider-retry.enabled=false",
                "loomspan.models.local.connection=local", "loomspan.models.local.provider-model=fixture",
                "loomspan.execution-trace.persistence=always", "loomspan.session.mission-timeout=15s").run();
        skills = context.getBean(SkillTemplate.class); reloader = context.getBean(SkillReloader.class);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("local-support-user", "unused",
                        org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_SUPPORT_TRIAGE")));
    }
    void publish(List<SkillDocument> documents, Map<String,Object> settings) {
        String configuration = "loomspan:\n  executor-config:\n    default:\n      typesafe: " + JSON.writeValueAsString(settings) + "\n"
                + "  connections:\n    local: {driver: openai, base-url: '" + planner.url("/v1") + "', api-key-ref: planner-key, provider-retry: {enabled: false}}\n"
                + "  models:\n    local: {connection: local, provider-model: fixture}\n  execution-trace: {persistence: always}\n";
        try (var update = reloader.prepare("default", documents, new ExecutionConfiguration(configuration), Map.of("planner-key", "planner-test-key"))) { reloader.publish(update); }
    }
    void publish(Map<String,Object> config) { publish(List.of(leaf("decision", config)), settings()); }
    Map<String,Object> settings() { return Map.of("api_key", "namespace-test-key", "base_url", provider.url("/prefix").toString()); }
    static SkillDocument leaf(String name, Map<String,Object> config) {
        return new SkillDocument(name + ".yaml", "name: " + name + "\ndescription: Native request\nexecutor: typesafe\nconfig: " + JSON.writeValueAsString(config) + "\n");
    }
    static Map<String,Object> nativeRequest(Object state, String type) {
        var question = new LinkedHashMap<String,Object>(); question.put("type", type); question.put("instructions", "Judge the supplied state");
        if (type.equals("choice")) question.put("criteria", Map.of("yes", "Fits", "other", "Does not fit"));
        if (type.equals("score")) question.put("criteria", List.of("Low", Map.of("level", "High")));
        var request = new LinkedHashMap<String,Object>(); request.put("model", "jev-latest"); request.put("state", state); request.put("questions", Map.of("identity/kept", question));
        return request;
    }
    String invoke(Map<String,Object> input) { return skills.invoke("decision", input, views::add); }
    void response(String body) { provider.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body)); }
    RecordedRequest take() throws Exception { return provider.takeRequest(2, TimeUnit.SECONDS); }
    List<JsonNode> trace() throws Exception {
        String session = views.getLast().sessionId();
        try (var files = Files.walk(Path.of(System.getProperty("java.io.tmpdir")))) {
            Path file = files.filter(p -> p.getFileName().toString().startsWith(session + ".") && p.toString().endsWith(".execution-trace.ndjson")).findFirst().orElseThrow();
            return Files.readAllLines(file).stream().filter(s -> !s.isBlank()).map(JSON::readTree).toList();
        }
    }
    static MockResponse text(Object value) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(JSON.writeValueAsString(Map.of(
                "id", "fixture", "object", "response", "created_at", 1, "model", "fixture", "status", "completed",
                "output", List.of(Map.of("id", "msg_0", "type", "message", "role", "assistant", "status", "completed",
                        "content", List.of(Map.of("type", "output_text", "text", JSON.writeValueAsString(value), "annotations", List.of())))),
                "usage", Map.of("input_tokens", 1, "output_tokens", 1, "total_tokens", 2))));
    }
    @Override public void close() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        context.close(); provider.close(); planner.close();
    }
}
