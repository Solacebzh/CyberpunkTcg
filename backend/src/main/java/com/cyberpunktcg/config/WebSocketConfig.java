package com.cyberpunktcg.config;

import com.cyberpunktcg.ws.StompAuthInterceptor;
import com.cyberpunktcg.ws.WsDestinations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * Configuration temps réel : endpoint STOMP sur WebSocket.
 *
 * <pre>
 *   Client                                       Serveur
 *     |  CONNECT  ws://host/ws  (header pseudo)    |
 *     |  SUBSCRIBE /topic/lobby/{code}             |  état des salons
 *     |  SUBSCRIBE /topic/game/{id}/{pseudo}       |  état masqué de la partie
 *     |  SEND      /app/lobby.create        -->    |  intentions de lobby
 *     |  SEND      /app/game/{id}/action    -->    |  commandes de jeu
 *     |  SUBSCRIBE /user/queue/errors              |  erreurs privées
 * </pre>
 *
 * <p>Le broker « simple » en mémoire suffit pour une partie entre amis sur une instance.
 * Le jour où l'on scale horizontalement, il sera remplacé par un broker externe
 * (RabbitMQ / ActiveMQ) sans changer le contrat client.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Origines autorisées (frontend Vite en dev, domaine de prod en prod). */
    private final List<String> allowedOrigins;
    private final StompAuthInterceptor authInterceptor;

    public WebSocketConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins,
                           StompAuthInterceptor authInterceptor) {
        this.allowedOrigins = allowedOrigins;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(WsDestinations.ENDPOINT)
                // En dev : Vite (5173) et le proxy de prévisualisation Arena.
                .setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Auth simplifiée par pseudo (en-tête du frame CONNECT), sans JWT.
        registration.interceptors(authInterceptor);
        // Pool dédié : les messages clients ne doivent jamais être bloqués par
        // les heartbeats du broker (qui ont leur propre scheduler).
        registration.taskExecutor(channelExecutor("ws-inbound-", 4));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(channelExecutor("ws-outbound-", 4));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Scheduler DÉDIÉ et interne (pas un bean global) pour les heartbeats.
        registry.enableSimpleBroker(WsDestinations.TOPIC_PREFIX, "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler());
        // Destinations traitées par nos @MessageMapping
        registry.setApplicationDestinationPrefixes(WsDestinations.APP_PREFIX);
        // Destinations privées : convertAndSendToUser(...) → /user/{name}/queue/...
        registry.setUserDestinationPrefix(WsDestinations.USER_PREFIX);
    }

    private ThreadPoolTaskExecutor channelExecutor(String threadPrefix, int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize * 2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    /** Planificateur dédié aux heartbeats du broker simple (thread daemon). */
    private ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
