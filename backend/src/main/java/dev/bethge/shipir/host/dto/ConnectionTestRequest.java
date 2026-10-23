package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.host.ConnectionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Same connection fields as {@link CreateHostRequest}, without a name: nothing is stored. */
public record ConnectionTestRequest(
        @NotNull ConnectionType connectionType,
        @Size(max = 255) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(max = HostLimits.MAX_PEM_CHARS) String tlsCaPem,
        @Size(max = HostLimits.MAX_PEM_CHARS) String tlsCertPem,
        @Size(max = HostLimits.MAX_PEM_CHARS) String tlsKeyPem) {

    /** Never print the PEMs. */
    @Override
    public String toString() {
        return "ConnectionTestRequest[connectionType=" + connectionType + "]";
    }
}
