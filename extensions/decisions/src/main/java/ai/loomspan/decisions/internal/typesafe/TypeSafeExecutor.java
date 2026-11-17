package ai.loomspan.decisions.internal.typesafe;

import ai.loomspan.api.SkillExecutor;
import ai.loomspan.api.extensions.v1.SkillExecutorInvocation;
import tools.jackson.databind.json.JsonMapper;

/** Pack wiring detail; applications declare ordinary executor YAML skills. */
public final class TypeSafeExecutor {
    private final TypeSafeTransport transport;
    private final JsonMapper json = JsonMapper.builder().build();

    public TypeSafeExecutor(TypeSafeTransport transport) { this.transport = transport; }

    @SkillExecutor(name = "typesafe", config = "typesafe")
    public String execute(SkillExecutorInvocation invocation) {
        byte[] request = TypeSafeRequest.prepare(invocation.skillConfig(), invocation.executorConfig(), invocation.input());
        return json.writeValueAsString(transport.send(request, invocation));
    }
}
