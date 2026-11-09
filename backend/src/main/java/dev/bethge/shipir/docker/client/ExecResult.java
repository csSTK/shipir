package dev.bethge.shipir.docker.client;

/** Outcome of a finished non-interactive exec: the process' exit code and its merged stdout/stderr (UTF-8, tail only if it exceeded the cap). */
public record ExecResult(int exitCode, String output) {
}
