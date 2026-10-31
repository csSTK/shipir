package dev.bethge.shipir.compose.portainer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Where to reach Portainer and the API token (used for this request only, never stored). */
public record PortainerConnectRequest(@NotBlank @Size(max = 2048) String url,
                                      @NotBlank @Size(max = 4096) String apiToken) {

    @Override
    public String toString() {
        return "PortainerConnectRequest[url=" + url + ", apiToken=***]";
    }
}
