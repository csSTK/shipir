package dev.bethge.shipir.ai.tools;

/** Outcome of {@link AiToolRegistry#propose}: either a pending action to put in front of the user, or an error for the model. */
public record ProposalResult(PendingAction action, ToolResult error) {

    public boolean accepted() {
        return action != null;
    }

    static ProposalResult accepted(PendingAction action) {
        return new ProposalResult(action, null);
    }

    static ProposalResult rejected(ToolResult error) {
        return new ProposalResult(null, error);
    }
}
