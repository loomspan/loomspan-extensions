package ai.loomspan.examples.triage;

import ai.loomspan.api.ExecutionConfiguration;
import ai.loomspan.api.SkillDocument;
import ai.loomspan.api.SkillReloader;
import ai.loomspan.api.SkillTemplate;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootConfiguration
@EnableAutoConfiguration
public class TriageApplication {
    static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String ILLUSTRATIVE = """
            {"model":"jev-stub","answers":{"team":{"type":"choice","choice":"returns","confidence":0.92,"probabilities":{"returns":0.92,"billing":0.03,"other":0.05}},"repeat":{"type":"noul","noul":0.9},"impact":{"type":"score","score":1.8,"confidence":0.85,"legend":{"0":"Minor","1":"Partial","2":"Unusable"},"probabilities":{"0":0.05,"1":0.1,"2":0.85}}},"usage":{"input_tokens":120,"output_tokens":12},"fixture":"illustrative only"}
            """;

    public static void main(String[] args) throws Exception {
        // Default is deterministic local proof. Optional live use takes resolved values as arguments.
        HttpServer stub = null;
        String endpoint; String key;
        if (args.length == 0) {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/v1/systemone", exchange -> {
                System.out.println("Stub request: " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = ILLUSTRATIVE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            stub.start(); endpoint = "http://127.0.0.1:" + stub.getAddress().getPort(); key = "local-test-key";
        } else {
            if (args.length != 2) throw new IllegalArgumentException("Supply resolved base URL and API key, or no arguments for local stub");
            endpoint = args[0]; key = args[1];
        }
        try (var context = new SpringApplicationBuilder(TriageApplication.class).web(org.springframework.boot.WebApplicationType.NONE)
                .properties("loomspan.skills.locations=classpath:/no-startup-skills/*.yaml", "loomspan.execution-trace.persistence=always").run()) {
            String yaml;
            try (var resource = TriageApplication.class.getResourceAsStream("/triage.yaml")) { yaml = new String(resource.readAllBytes(), StandardCharsets.UTF_8); }
            var settings = new ExecutionConfiguration("loomspan:\n  executor-config:\n    default:\n      typesafe: " + JSON.writeValueAsString(Map.of("api_key", key, "base_url", endpoint)) + "\n");
            var reloader = context.getBean(SkillReloader.class);
            try (var update = reloader.prepare("default", List.of(new SkillDocument("triage.yaml", yaml)), settings)) { reloader.publish(update); }
            try {
                // Local demo identity. A real host installs its authenticated caller and grants this role.
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("local-support-user", "unused",
                                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_SUPPORT_TRIAGE")));
                String result = context.getBean(SkillTemplate.class).invoke("triage", Map.of("ticket", Map.of("message", "Parcel arrived damaged and is unusable", "contacts", 2)),
                        view -> System.out.println("Trace session: " + view.sessionId()));
                System.out.println("Native result: " + result);
                System.out.println("Illustrative policy: " + action(JSON.readTree(result)));
            } catch (RuntimeException failure) {
                System.out.println("Application policy: manual review after execution error: " + failure.getMessage());
                throw failure;
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        } finally { if (stub != null) stub.stop(0); }
    }

    /** Illustrative application policy; thresholds require evaluation on real application data. */
    public static String action(JsonNode result) {
        if (result.has("refusal") || result.at("/answers/team/refusal").isString()) return "manual review: refusal";
        var team = result.at("/answers/team/choice");
        if (!team.isString() || result.at("/answers/team/confidence").asDouble(0) < 0.8) return "manual review: uncertain routing";
        return "route to " + team.asText() + (result.at("/answers/impact/score").asDouble(0) >= 1.5 ? "; priority review" : "; standard review");
    }
}
