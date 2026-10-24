package dev.bethge.shipir.ai.llm;

import dev.bethge.shipir.ai.settings.AiProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Selects the {@link LlmClient} implementation for a provider. */
@Component
public class LlmClients {

    private final LlmClient openAiCompatible;
    private final LlmClient gemini;

    public LlmClients(@Qualifier("openAiCompatibleLlmClient") LlmClient openAiCompatible,
                      @Qualifier("geminiLlmClient") LlmClient gemini) {
        this.openAiCompatible = openAiCompatible;
        this.gemini = gemini;
    }

    public LlmClient forProvider(AiProvider provider) {
        return switch (provider) {
            case GEMINI -> gemini;
            case OPENAI_COMPATIBLE -> openAiCompatible;
        };
    }
}
