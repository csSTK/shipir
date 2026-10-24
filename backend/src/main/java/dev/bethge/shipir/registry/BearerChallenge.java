package dev.bethge.shipir.registry;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A parsed {@code WWW-Authenticate: Bearer realm="..",service=".."} challenge of a Registry v2 API. */
record BearerChallenge(String realm, String service) {

    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    /** @return the challenge, or null if {@code header} is not a Bearer challenge */
    static BearerChallenge parse(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer", 0, 6)) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(header);
        while (m.find()) {
            params.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        return new BearerChallenge(params.get("realm"), params.get("service"));
    }

    /**
     * Token endpoint URL (realm plus service and optional scope), or null when the realm is missing or unsafe
     * (https only, no user info; http only when {@code allowInsecureHttp}).
     */
    URI tokenUri(boolean allowInsecureHttp, String scope) {
        if (realm == null) {
            return null;
        }
        URI realmUri = URI.create(realm);
        String scheme = realmUri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme)
                || (allowInsecureHttp && "http".equalsIgnoreCase(scheme));
        if (!schemeOk || realmUri.getHost() == null || realmUri.getUserInfo() != null) {
            return null;
        }
        StringBuilder query = new StringBuilder(realmUri.getRawQuery() == null ? "" : realmUri.getRawQuery());
        append(query, "service", service);
        append(query, "scope", scope);
        return URI.create(realm.replaceFirst("\\?.*$", "") + (query.isEmpty() ? "" : "?" + query));
    }

    private static void append(StringBuilder query, String name, String value) {
        if (value == null) {
            return;
        }
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
