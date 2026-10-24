package dev.bethge.shipir.ai.settings;

/** Decrypted effective LLM configuration of the active provider, for in-process use by the chat orchestration only. */
public record AiConfig(AiProvider provider, String baseUrl, String model, String apiKey) {

    /** The key is left out. */
    @Override
    public String toString() {
        return "AiConfig[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model + ", apiKey=<hidden>]";
    }
}
