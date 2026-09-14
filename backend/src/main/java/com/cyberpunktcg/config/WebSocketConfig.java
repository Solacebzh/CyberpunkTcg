package com.cyberpunktcg.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * Configuration temps réel : endpoint STOMP sur WebSocket.
 *
 * <pre>
 *   Client                                   Serveur
 *     |  CONNECT  ws://host/ws                  |
 *     |  SUBSCRIBE /topic/room.{id}            |  diffusion d'état (broadcast)
 *     |  SEND      /app/room.{id}.action  -->  |  intention joueur (traitée par @MessageMapping)
 *     |  SUBSCRIBE /user/queue/errors          |  erreurs/rejets adressés au seul joueur
 * </pre>
 *
 * <p>Le broker « simple » en mémoire suffit pour une partie entre amis sur une instance.
 * Le jour où l'on scale horizontalement, il sera remplacé par un broker externe
 * (RabbitMQ / ActiveMQ) sans changer le contrat client : {@link MessageBrokerRegistry#enableStompBrokerRelay}.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Origines autorisées (frontend Vite en dev, domaine de prod en prod). */
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Value("${app.websocket.endpoint:/ws}")
    private String endpoint;

    @Value("${app.websocket.application-prefix:/app}")
    private String applicationPrefix;

    @Value("${app.websocket.broker-prefix:/topic}")
    private String brokerPrefix;

    @Value("${app.websocket.user-prefix:/queue}")
    private String userPrefix;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(endpoint)
                // En dev : Vite (5173) et le proxy de prévisualisation Arena.
                .setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Destinations diffusées aux abonnés (état de partie, chat de lobby…)
        registry.enableSimpleBroker(brokerPrefix, userPrefix);
        // Destinations traitées par nos @MessageMapping
        registry.setApplicationDestinationPrefixes(applicationPrefix);
        // Destinations privées : /user/{session}/queue/...
        registry.setUserDestinationPrefix("/user");
    }
}
