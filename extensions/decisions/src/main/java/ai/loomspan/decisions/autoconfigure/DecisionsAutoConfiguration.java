package ai.loomspan.decisions.autoconfigure;

import ai.loomspan.decisions.internal.typesafe.TypeSafeTransport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Pack construction machinery, not a bean replacement contract. */
@AutoConfiguration
public class DecisionsAutoConfiguration {
    @Bean(destroyMethod = "close")
    TypeSafeTransport typeSafeTransport() {
        return new TypeSafeTransport();
    }
}
