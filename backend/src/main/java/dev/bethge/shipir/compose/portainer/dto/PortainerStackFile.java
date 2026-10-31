package dev.bethge.shipir.compose.portainer.dto;

import java.util.List;

/** A stack's Compose file and environment, ready to prefill the add-deployment flow. */
public record PortainerStackFile(String name, String yaml, List<PortainerEnvVar> env) {

    @Override
    public String toString() {
        return "PortainerStackFile[name=" + name + "]";
    }
}
