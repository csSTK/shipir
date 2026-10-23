package dev.bethge.shipir.common.config;

import dev.bethge.shipir.streaming.CsrfHandshakeInterceptor;
import dev.bethge.shipir.streaming.StompSecurityInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over plain WebSocket at {@code /ws}. The handshake is an HTTP request and therefore goes through the
 * session-cookie security filter chain (unauthenticated: 401); cross-origin handshakes are refused (default
 * same-origin check). Frame-level rules (CSRF on CONNECT, subscription destinations) live in the inbound interceptor.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompSecurityInterceptor stompSecurityInterceptor;

    WebSocketConfig(StompSecurityInterceptor stompSecurityInterceptor) {
        this.stompSecurityInterceptor = stompSecurityInterceptor;
    }

    /** Publishes session destroy/id-change events so consoles end with the HTTP session they were started from. */
    @Bean
    static HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").addInterceptors(new CsrfHandshakeInterceptor());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompSecurityInterceptor);
    }
}
