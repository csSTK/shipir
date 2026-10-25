package dev.bethge.shipir.common.net;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from {@code shipir.egress.*}. {@code allowPrivateRanges} is the opt-in for self-hosters whose git server,
 * registry or identity provider lives on loopback or a private network (RFC 1918, ULA, CGNAT). Link-local addresses
 * and cloud metadata endpoints stay blocked either way.
 */
@ConfigurationProperties("shipir.egress")
public record EgressProperties(@DefaultValue("false") boolean allowPrivateRanges) {
}
