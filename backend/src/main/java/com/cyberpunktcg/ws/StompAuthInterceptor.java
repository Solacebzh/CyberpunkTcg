package com.cyberpunktcg.ws;

import com.cyberpunktcg.lobby.LobbyException;
import com.cyberpunktcg.lobby.LobbyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authentification simplifiée (sans JWT) : le frame STOMP CONNECT doit porter
 * un en-tête natif {@code pseudo}. L'identité devient un
 * {@link StompPrincipal} attaché à tous les messages de la session ; les
 * contrôleurs s'en servent pour identifier l'émetteur sans jamais faire
 * confiance au corps des messages.
 *
 * <p>Un CONNECT sans pseudo valide est rejeté (frame ERROR, session close).</p>
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    /** En-tête natif attendu sur le frame CONNECT. */
    public static final String PSEUDO_HEADER = "pseudo";

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
        String pseudo = accessor.getFirstNativeHeader(PSEUDO_HEADER);
        try {
            LobbyService.requireValidPseudo(pseudo);
        } catch (LobbyException invalid) {
            log.warn("Connexion STOMP refusée : {}", invalid.getMessage());
            throw new MessageDeliveryException("Pseudo invalide ou absent (en-tête '"
                    + PSEUDO_HEADER + "' requis sur CONNECT) : " + invalid.getMessage());
        }
        accessor.setUser(new StompPrincipal(pseudo.trim()));
        log.debug("Session STOMP authentifiée : {}", pseudo);
        return message;
    }
}
