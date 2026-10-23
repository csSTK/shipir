package dev.bethge.shipir.ai.tools;

/** Outcome handed back to the model; failures are results, never exceptions, so the model can react to them. */
public record ToolResult(boolean error, String content) {

    public static ToolResult ok(String content) {
        return new ToolResult(false, content);
    }

    public static ToolResult error(String code, String message) {
        return new ToolResult(true, "{\"error\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}");
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> out.append(c < 0x20 ? ' ' : c);
            }
        }
        return out.toString();
    }
}
