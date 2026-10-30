package dev.bethge.shipir.proxy;

import java.util.List;
import java.util.Map;

/** Unvalidated route data as it arrives from the API; {@link ProxyRouteValidator} decides whether it is acceptable. */
public record RouteInput(String hostname, List<String> aliases, String containerName, Integer targetPort,
                         BackendScheme backendScheme, String pathPrefix, String entrypoint, CertMode certMode,
                         Map<String, String> basicAuthUsers, Map<String, String> responseHeaders) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String hostname;
        private List<String> aliases = List.of();
        private String containerName;
        private Integer targetPort;
        private BackendScheme backendScheme;
        private String pathPrefix;
        private String entrypoint;
        private CertMode certMode;
        private Map<String, String> basicAuthUsers;
        private Map<String, String> responseHeaders;

        public Builder hostname(String v) { hostname = v; return this; }
        public Builder aliases(List<String> v) { aliases = v; return this; }
        public Builder containerName(String v) { containerName = v; return this; }
        public Builder targetPort(Integer v) { targetPort = v; return this; }
        public Builder backendScheme(BackendScheme v) { backendScheme = v; return this; }
        public Builder pathPrefix(String v) { pathPrefix = v; return this; }
        public Builder entrypoint(String v) { entrypoint = v; return this; }
        public Builder certMode(CertMode v) { certMode = v; return this; }
        public Builder basicAuthUsers(Map<String, String> v) { basicAuthUsers = v; return this; }
        public Builder responseHeaders(Map<String, String> v) { responseHeaders = v; return this; }

        public RouteInput build() {
            return new RouteInput(hostname, aliases, containerName, targetPort, backendScheme, pathPrefix, entrypoint,
                    certMode, basicAuthUsers, responseHeaders);
        }
    }
}
