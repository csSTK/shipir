package dev.bethge.shipir.ai.llm;

import java.util.List;
import java.util.function.Consumer;

/** Port to an LLM provider. Connection data is passed per call; the key is never stored or logged. */
public interface LlmClient {

    /**
     * Runs one streamed chat completion. {@code onToken} receives each text delta as it arrives.
     *
     * @throws LlmException with a {@link LlmException.Code}
     */
    LlmResponse chat(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
                     List<LlmToolDefinition> tools, Consumer<String> onToken);
}
