package ai.loomspan.decisions.autoconfigure;

import ai.loomspan.decisions.internal.typesafe.TypeSafeTransport;
import ai.loomspan.decisions.internal.typesafe.TypeSafeExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Pack construction machinery, not a bean replacement contract. */
@AutoConfiguration
public class DecisionsAutoConfiguration {
    @Bean
    TypeSafeExecutor typeSafeExecutor(TypeSafeTransport transport) {
        return new TypeSafeExecutor(transport);
    }

    @Bean(destroyMethod = "close")
    TypeSafeTransport typeSafeTransport() {
        return new TypeSafeTransport();
    }
}
