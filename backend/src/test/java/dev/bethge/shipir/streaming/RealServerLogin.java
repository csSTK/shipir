package dev.bethge.shipir.streaming;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cookie-jar login against a real (RANDOM_PORT) server for the streaming tests: anonymous {@code GET /api/auth/me}
 * for the XSRF-TOKEN cookie, then {@code POST /api/auth/login}. Every step is asserted, so a failure names the
 * status, headers and body of the response that broke it instead of surfacing later as a null header value
 * (shipr-k31n.24, shipr-bq7o).
 */
final class RealServerLogin {

    private RealServerLogin() {
    }

    /** Sends the request with the jar's cookies and stores the cookies the response sets. */
    static HttpResponse<String> call(HttpClient http, Map<String, String> jar, HttpRequest.Builder b)
            throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        r.headers().allValues("set-cookie").forEach(c -> {
            String pair = c.split(";", 2)[0];
            int i = pair.indexOf('=');
            jar.put(pair.substring(0, i), pair.substring(i + 1));
        });
        return r;
    }

    /** Logs in and returns the XSRF-TOKEN issued for the authenticated session; the jar then holds the session. */
    static String login(HttpClient http, Map<String, String> jar, int port, String username, String password)
            throws Exception {
        // 127.0.0.1, not "localhost": that can resolve to ::1 first, where another process on the shared machine
        // (a Vite dev server, another worktree's test JVM) may hold the same port and answer without our cookies.
        String base = "http://127.0.0.1:" + port;
        HttpResponse<String> me = call(http, jar, HttpRequest.newBuilder(URI.create(base + "/api/auth/me")));
        assertThat(jar.get("XSRF-TOKEN"))
                .as("XSRF-TOKEN cookie from GET /api/auth/me: status %s, headers %s, body %s",
                        me.statusCode(), me.headers().map(), me.body())
                .isNotNull();
        HttpResponse<String> r = call(http, jar, HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")));
        assertThat(r.statusCode()).as("login status %s, headers %s, body %s", r.statusCode(), r.headers().map(),
                r.body()).isEqualTo(200);
        assertThat(jar.get("JSESSIONID")).as("JSESSIONID cookie after login").isNotNull();
        assertThat(jar.get("XSRF-TOKEN")).as("XSRF-TOKEN cookie after login").isNotNull();
        return jar.get("XSRF-TOKEN");
    }
}
