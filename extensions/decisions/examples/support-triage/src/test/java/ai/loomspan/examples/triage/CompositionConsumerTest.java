package ai.loomspan.examples.triage;

import ai.loomspan.api.SkillDocument;
import ai.loomspan.api.SkillException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static ai.loomspan.examples.triage.ConsumerHarness.*;

class CompositionConsumerTest {
    static String resource(String name) throws Exception {
        try (var stream = CompositionConsumerTest.class.getResourceAsStream("/" + name + ".yaml")) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static Map<String,Object> task(String name, List<String> dependencies) {
        var task = new LinkedHashMap<String,Object>();
        task.put("taskId", name); task.put("title", name); task.put("status", "PENDING"); task.put("capabilityName", "default-" + name);
        task.put("intent", "Assess support ticket"); task.put("dependsOn", dependencies); task.put("expectedOutputs", List.of("assessment"));
        task.put("parallelGroup", null); task.put("note", ""); return task;
    }
    @Test void realChildrenForwardFullJsonAndEnforceRuntimeResultBindings() throws Exception {
        try (var h = new ConsumerHarness()) {
            var docs = List.of(new SkillDocument("triage", resource("triage")), new SkillDocument("assess", resource("assess")), new SkillDocument("parent", resource("parent")));
            h.publish(docs, h.settings());
            for (String answer : List.of(TriageApplication.ILLUSTRATIVE, "{\"answers\":{\"repeat\":{}}}", "{\"answers\":{\"repeat\":{\"noul\":\"wrong type\"}}}")) {
                h.planner.enqueue(text(Map.of("capabilityName", "default-parent", "createdAt", "2026-11-16T00:00:00Z", "status", "VALID",
                        "tasks", List.of(task("triage", List.of()), task("assess", List.of("triage"))))));
                h.response(answer);
                int before = h.provider.getRequestCount();
                if (answer.equals(TriageApplication.ILLUSTRATIVE)) {
                    String complete = "{\"answers\":{\"followup\":{\"noul\":0.7}},\"usage\":{\"input_tokens\":7},\"unknown\":true}";
                    h.response(complete);
                    var ticket = Map.<String,Object>of("message", "Damaged twice");
                    assertThat(h.skills.invoke("parent", Map.of("ticket", ticket), h.views::add)).isEqualTo(complete);
                    var triage = TypeSafeConsumerTest.expectedTriageRequest(ticket);
                    assertThat(JSON.readTree(h.take().getBody().readUtf8())).isEqualTo(JSON.readTree(JSON.writeValueAsString(triage)));
                    var assess = JSON.readTree(h.take().getBody().readUtf8());
                    assertThat(assess).isEqualTo(JSON.readTree("""
                            {"model":"jev-latest","state":{"probability":0.9},
                             "questions":{"followup":{"type":"noul","instructions":"Given the repeat probability, does a human follow-up seem useful?"}}}
                            """));
                    assertThat(h.provider.getRequestCount()).isEqualTo(before + 2);
                } else {
                    assertThatThrownBy(() -> h.skills.invoke("parent", Map.of("ticket", Map.of("message", "Damaged")), h.views::add)).isInstanceOf(SkillException.class);
                    assertThat(h.provider.getRequestCount()).isEqualTo(before + 1); h.take();
                }
            }
            assertThat(h.planner.getRequestCount()).isEqualTo(3); // fully bound children use automatic assigned dispatch
        }
    }
}
