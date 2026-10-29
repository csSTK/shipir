package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool definitions are sent to the model with every request, so their size counts against the provider's
 * tokens-per-minute limit. This keeps them from growing back unnoticed.
 */
class ToolDefinitionBudgetTest {

    /** Approximate tokens (chars / 4). Before shortening: 9956 chars (about 2490 tokens); then 7154 chars (about 1788 tokens); then 7738 chars (about 1934 tokens) after adding list_config_files and read_config_file; then 8081 chars after adding list_access_grants; now 8044 chars (about 2011 tokens) after shortening its description; the budget (2015) is 81.1% of the old size. */
    // Adding a tool or a longer description means deliberately bumping this budget. Bumped from 1790 to 1940 for the two config
    // file read tools (shipr-006w.2, 584 chars, descriptions already minimal), and from 1940 to 2030 for the ADMIN-only
    // list_access_grants (shipr-006w.3, 343 chars: four uuid properties are the bulk; lowered to 2015 once its description was shortened, the per-id uuid schema is already one shared constant). It is only sent to ADMINs; keep the budget
    // below the 9956 chars measured before shortening, so the saving is not given back piecemeal.
    private static final int BUDGET_TOKENS = 2_015;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void adminToolDefinitionsStayWithinTheTokenBudget() throws Exception {
        AiToolRegistry registry = new AiToolRegistry(allTools(), MAPPER);

        String json = MAPPER.writeValueAsString(registry.definitionsFor(Role.ADMIN));
        int approxTokens = json.length() / 4;

        assertThat(registry.definitionsFor(Role.ADMIN)).isNotEmpty();
        assertThat(approxTokens)
                .as("approx tokens of serialized ADMIN tool definitions (%d chars)", json.length())
                .isLessThanOrEqualTo(BUDGET_TOKENS);
    }

    /** Builds every {@code @Bean AiTool} of both configs with mocked services; the handlers are never run. */
    private static List<AiTool> allTools() throws Exception {
        List<AiTool> tools = new ArrayList<>();
        for (Object config : List.of(new ReadToolsConfig(MAPPER), new WriteToolsConfig(MAPPER))) {
            for (Method m : config.getClass().getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Bean.class)) {
                    continue;
                }
                m.setAccessible(true);
                Object[] args = new Object[m.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = Mockito.mock(m.getParameterTypes()[i]);
                }
                tools.add((AiTool) m.invoke(config, args));
            }
        }
        return tools;
    }
}
