package dev.bethge.shipir.ai.settings;

/** Decrypted effective LLM configuration, for in-process use by the chat orchestration only. */
public record AiConfig(String baseUrl, String model, String apiKey) {

    /** The key is left out. */
    @Override
    public String toString() {
        return "AiConfig[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=<hidden>]";
    }
}
