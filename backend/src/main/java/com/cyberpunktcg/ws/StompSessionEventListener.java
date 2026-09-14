package com.cyberpunktcg.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

/**
 * Traduit les événements de cycle de vie STOMP en suivi de présence :
 * connexion (annulation d'une éventuelle déconnexion) et déconnexion
 * (départ du salon ou minuteur de forfait 120 s).
 */
@Component
public class StompSessionEventListener {

    private static final Logger log = LoggerFactory.getLogger(StompSessionEventListener.class);

    private final GamePresenceService presenceService;

    public StompSessionEventListener(GamePresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return;
        }
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        presenceService.onConnected(principal.getName(), sessionId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return;
        }
        log.debug("Session STOMP fermée : {} ({})", principal.getName(), event.getSessionId());
        presenceService.onDisconnected(event.getSessionId());
    }
}
