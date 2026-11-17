package ai.loomspan.examples.triage;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static ai.loomspan.examples.triage.ConsumerHarness.JSON;

/** External Console process using normal pairing/cookie/tab/CSRF, import and MCP protocols. */
final class ConsoleProcess implements AutoCloseable {
    final Process process;
    final Path log;
    final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
    String origin, tab, csrf, credential;
    int rpc;
    ConsoleProcess() throws Exception {
        String executable = System.getProperty("loomspan.console.executable", "");
        assertThat(executable).as("-Pconsole requires -Dloomspan.console.executable pointing to the canonical versioned build").isNotBlank().doesNotContain("${");
        assertThat(Files.isExecutable(Path.of(executable))).as("Console executable").isTrue();
        // Windows profile ownership requires the current user's protected temp parent;
        // a shared checkout can grant only inherited Modify and deny setting owner/DACL.
        Path temporary = Path.of(Optional.ofNullable(System.getenv("TEMP")).orElse(System.getProperty("java.io.tmpdir")));
        Path root = Files.createTempDirectory(temporary, "loomspan-console-consumer-");
        log = Path.of("target", root.getFileName() + ".log").toAbsolutePath();
        process = new ProcessBuilder(executable, "--config", root.resolve("profile/config.yaml").toString(), "--work-dir", root.resolve("workspace").toString(),
                "--listen", "127.0.0.1:0", "--no-open-browser").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            var pairing = Pattern.compile("Pairing URL: (http://[^\\s]+)"); String url = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                String output = Files.readString(log); var match = pairing.matcher(output);
                if (match.find()) { url = match.group(1); break; }
                if (!process.isAlive()) throw new AssertionError("Console exited: " + output);
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertThat(url).as("Pairing URL; see %s", log).isNotNull();
            URI parsed = URI.create(url); origin = parsed.getScheme() + "://" + parsed.getAuthority();
            String secret = parsed.getFragment(); assertThat(secret).startsWith("/pair/"); secret = secret.substring("/pair/".length());
            browser("/pairing/exchange", Map.of("secret", secret));
            var bootstrap = browser("/bootstrap", Map.of()); tab = bootstrap.path("tabId").asText(); csrf = bootstrap.path("csrfToken").asText();
            assertThat(bootstrap.path("consoleVersion").asText()).isEqualTo("1.0.0-beta.10-SNAPSHOT");
            credential = browser("/mcp/enable", Map.of()).path("credential").asText(); assertThat(credential).isNotBlank();
            var initialized = rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "decisions-consumer", "version", "0.1.0-SNAPSHOT")));
            assertThat(initialized.path("serverInfo").path("version").asText()).isEqualTo("1.0.0-beta.10-SNAPSHOT");
        } catch (Throwable failure) { close(); throw failure; }
    }
    JsonNode browser(String path, Object body) throws Exception {
        return send("/api/console/v1" + path, JSON.writeValueAsBytes(body), "application/json", false);
    }
    JsonNode importTrace(Path file) throws Exception { return send("/api/console/v1/artifacts/import", Files.readAllBytes(file), "application/x-ndjson", false); }
    JsonNode send(String path, byte[] body, String type, boolean mcp) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (mcp) request.header("Authorization", "Bearer " + credential).header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-06-18");
        else {
            request.header("Origin", origin);
            if (tab != null) request.header("X-loomspan-Console-Tab", tab).header("X-loomspan-Console-CSRF", csrf);
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s response %s; process log %s", path, response.body(), log).isEqualTo(200);
        return JSON.readTree(response.body());
    }
    JsonNode rpc(String method, Object arguments) throws Exception {
        var result = send("/mcp", JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", ++rpc, "method", method, "params", arguments)), "application/json", true);
        assertThat(result.has("error")).as("MCP %s: %s", method, result).isFalse(); return result.path("result");
    }
    JsonNode tool(String name, Object arguments) throws Exception {
        JsonNode result = rpc("tools/call", Map.of("name", name, "arguments", arguments));
        assertThat(result.path("isError").asBoolean()).as("%s: %s", name, result).isFalse();
        return result.path("structuredContent").path("result");
    }
    @Override public void close() throws Exception {
        try { if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); } } }
        finally { http.close(); }
    }
}
