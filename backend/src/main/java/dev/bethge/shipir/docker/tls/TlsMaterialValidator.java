package dev.bethge.shipir.docker.tls;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;

/** Lets other features (host/) reject unusable PEM on save, without touching the network or docker-java. */
public final class TlsMaterialValidator {

    private TlsMaterialValidator() {
    }

    /**
     * @throws DockerConnectionException with code {@code INVALID_TLS_MATERIAL} if CA, certificate or key
     *                                   is missing, not PEM, encrypted, of an unsupported type or mismatched
     */
    public static void validate(TlsMaterial material) {
        TlsMaterialParser.parse(material);
    }
}
