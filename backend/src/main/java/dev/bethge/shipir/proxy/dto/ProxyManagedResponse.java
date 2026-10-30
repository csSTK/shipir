package dev.bethge.shipir.proxy.dto;

/** Host view: the managed {@code instance}, or null together with why a setup is not possible ({@code setupBlockedReason} null = possible). */
public record ProxyManagedResponse(ProxyInstanceView instance, SetupBlockedReason setupBlockedReason) {
}
