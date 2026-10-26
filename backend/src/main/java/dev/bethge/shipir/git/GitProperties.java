package dev.bethge.shipir.git;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Bound from {@code shipir.git.*}. {@code allowInsecureHttp} exists only so tests can reach a local stub;
 * it stays off in every real deployment (https is the only accepted scheme). {@code connectTimeout} is only an
 * inactivity timeout of the transport; {@code cloneTimeout} is the overall deadline of one fetch and
 * {@code maxCheckoutSize} the cap for the checkout directory on disk (both abort the fetch and delete the directory).
 */
@ConfigurationProperties("shipir.git")
record GitProperties(@DefaultValue("false") boolean allowInsecureHttp,
                     @DefaultValue("10s") Duration connectTimeout,
                     @DefaultValue("10m") Duration cloneTimeout,
                     @DefaultValue("500MB") DataSize maxCheckoutSize) {
}
