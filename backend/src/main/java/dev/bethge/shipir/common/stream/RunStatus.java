package dev.bethge.shipir.common.stream;

/** Final state of a streamed run: a deployment, or a host-level image pull, build or push. */
public enum RunStatus {
    DONE, FAILED
}
