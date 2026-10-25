package dev.bethge.shipir.docker.dto;

import java.time.Duration;
import java.util.Map;

/**
 * What to build: Dockerfile path inside the context, the tag to apply, build arguments (visible in the image history,
 * so never secrets), the cap for the context and the overall build timeout. {@link #toString()} hides argument values.
 */
public record BuildSpec(String dockerfile, String tag, Map<String, String> buildArgs, boolean noCache,
                        long maxContextBytes, Duration timeout) {

    @Override
    public String toString() {
        return "BuildSpec[dockerfile=" + dockerfile + ", tag=" + tag + ", buildArgKeys=" + buildArgs.keySet()
                + ", noCache=" + noCache + "]";
    }
}
