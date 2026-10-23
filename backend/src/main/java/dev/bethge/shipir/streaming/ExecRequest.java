package dev.bethge.shipir.streaming;

/** Body of {@code POST .../exec}; every field is optional ({@code shell} /bin/sh or /bin/bash, size 80x24). */
public record ExecRequest(String shell, Integer cols, Integer rows) {
}
