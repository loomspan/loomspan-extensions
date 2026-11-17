package ai.loomspan.decisions.autoconfigure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.loomspan.api.extensions.v1.SkillExecutorInvocation;
import ai.loomspan.decisions.internal.typesafe.TypeSafeTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DecisionsAutoConfigurationTest {
    @Test void registrationCreatesOneTransportAndClosesItWithContext() throws Exception {
        String resource="META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try(var stream=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).contains(DecisionsAutoConfiguration.class.getName());
        }
        var captured=new java.util.concurrent.atomic.AtomicReference<TypeSafeTransport>();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DecisionsAutoConfiguration.class)).run(context -> {
            assertThat(context).hasSingleBean(TypeSafeTransport.class);
            var transport=context.getBean(TypeSafeTransport.class);
            assertThat(context.getBean(TypeSafeTransport.class)).isSameAs(transport); captured.set(transport);
        });
        var invocation=mock(SkillExecutorInvocation.class);
        assertThatThrownBy(() -> captured.get().send(new byte[]{123,125},invocation)).hasMessageContaining("closed");
        captured.get().close();
    }

    @Test void productionUsesOnlySupportedFrameworkContractAndNoBeanReplacementSpi() throws Exception {
        try(var paths=Files.walk(Path.of("src/main/java"))) {
            for(Path path:paths.filter(p->p.toString().endsWith(".java")).toList()) {
                String source=Files.readString(path);
                assertThat(source).doesNotContain("ai.loomspan.internal", "ai.loomspan.autoconfigure", "ConditionalOnMissingBean", "SkillReloader");
                for(String line:source.lines().filter(s->s.startsWith("import ai.loomspan.")&&!s.startsWith("import ai.loomspan.decisions.")).toList())
                    assertThat(line).isIn("import ai.loomspan.api.extensions.v1.SkillExecutorInvocation;","import ai.loomspan.api.extensions.v1.ExecutorDiagnostics;");
            }
        }
    }
}
