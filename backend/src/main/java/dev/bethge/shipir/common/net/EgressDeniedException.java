package dev.bethge.shipir.common.net;

import java.io.IOException;

/**
 * An outbound connection was refused by the {@link EgressPolicy}. An {@link IOException} so callers that already treat
 * network failures as "unreachable" need no extra handling. The message never names the address or the reason: callers
 * must not be able to tell a blocked internal host from an unreachable one.
 */
public class EgressDeniedException extends IOException {

    public EgressDeniedException() {
        super("Outbound connection not permitted");
    }
}
