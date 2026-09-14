package com.cyberpunktcg.api;

import com.cyberpunktcg.api.dto.PingRequest;
import com.cyberpunktcg.api.dto.PongResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.time.Instant;

/**
 * Poignée de main temps réel : sert de test de bout en bout du canal STOMP
 * (et de modèle pour les futurs contrôleurs de jeu).
 *
 * <p>Client → {@code SEND /app/ping} ; serveur → broadcast {@code /topic/pong}.</p>
 */
@Slf4j
@Controller
public class PingController {

    @MessageMapping("/ping")
    @SendTo("/topic/pong")
    public PongResponse ping(@Payload(required = false) PingRequest request) {
        String echo = request != null && request.message() != null ? request.message() : "ping";
        log.debug("Ping reçu : {}", echo);
        return new PongResponse("pong", echo, Instant.now());
    }
}
