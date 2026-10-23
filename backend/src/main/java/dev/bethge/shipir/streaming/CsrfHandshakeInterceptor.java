package dev.bethge.shipir.streaming;

import jakarta.servlet.http.Cookie;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Remembers the {@code XSRF-TOKEN} cookie of the (cookie-authenticated) handshake in the WebSocket session so the
 * STOMP CONNECT can prove it knows it (double submit). A cross-site page can open the socket with the victim's
 * cookies but cannot read the cookie, so it cannot satisfy CONNECT.
 */
public class CsrfHandshakeInterceptor implements HandshakeInterceptor {

    static final String CSRF_ATTRIBUTE = "csrfToken";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet && servlet.getServletRequest().getCookies() != null) {
            for (Cookie c : servlet.getServletRequest().getCookies()) {
                if ("XSRF-TOKEN".equals(c.getName())) {
                    attributes.put(CSRF_ATTRIBUTE, c.getValue());
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
