package dev.bethge.shipir.compose.execution;

import java.util.List;

/** Outcome of one compose run; {@code output} holds stdout and stderr lines in arrival order. */
public record ComposeProcessResult(int exitCode, boolean timedOut, List<String> output) {

    public ComposeProcessResult {
        output = List.copyOf(output);
    }

    public boolean success() {
        return exitCode == 0 && !timedOut;
    }
}
