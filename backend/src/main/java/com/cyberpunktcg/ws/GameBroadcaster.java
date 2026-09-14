package com.cyberpunktcg.ws;

import com.cyberpunktcg.api.dto.ws.GameLogEntryDTO;
import com.cyberpunktcg.api.dto.ws.GameNotice;
import com.cyberpunktcg.api.dto.ws.GameStateDTO;
import com.cyberpunktcg.api.dto.ws.GameStateMessage;
import com.cyberpunktcg.api.dto.ws.WsError;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diffusion des messages liés aux parties :
 * <ul>
 *   <li>états masqués PERSONNELS sur {@code /topic/game/{gameId}/{pseudo}} ;</li>
 *   <li>notifications publiques sur {@code /topic/game/{gameId}} ;</li>
 *   <li>erreurs privées sur {@code /user/queue/errors}.</li>
 * </ul>
 * La même poussée d'état est reconstruite pour chaque joueur avec son propre
 * masquage : un joueur ne reçoit jamais les secrets adverses.
 */
@Component
public class GameBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(GameBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final GameService gameService;
    /** Résolu paresseusement pour éviter un cycle de dépendances avec la présence. */
    private final ObjectProvider<GamePresenceService> presenceProvider;

    /** Numéro de séquence monotone par partie (détection de perte côté client). */
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public GameBroadcaster(SimpMessagingTemplate messagingTemplate,
                           GameService gameService,
                           ObjectProvider<GamePresenceService> presenceProvider) {
        this.messagingTemplate = messagingTemplate;
        this.gameService = gameService;
        this.presenceProvider = presenceProvider;
    }

    /** Démarrage d'une partie : notification publique + états initiaux pour les deux joueurs. */
    public void gameStarted(String gameId) {
        messagingTemplate.convertAndSend(WsDestinations.game(gameId), GameNotice.gameStarted(gameId));
        pushStates(gameId, null, List.of());
    }

    /**
     * Pousse l'état courant aux deux joueurs après une action acceptée.
     * Si la action a mis fin à la partie, diffuse aussi GAME_OVER.
     */
    public void afterAction(String gameId, String clientRequestId, List<GameEvent> producedEvents) {
        pushStates(gameId, clientRequestId, producedEvents);
        GameState internal = gameService.getGameStateInternal(gameId);
        if (internal.isGameOver()) {
            messagingTemplate.convertAndSend(WsDestinations.game(gameId),
                    GameNotice.gameOver(gameId, internal.getWinnerId(), internal.getEndReason()));
        }
    }

    /** Envoie la dernière vue masquée à un joueur unique (resync / reconnexion). */
    public void sendStateToPlayer(String gameId, String pseudo, String clientRequestId) {
        long sequence = sequenceOf(gameId).get();
        GameState masked = gameService.getGameState(gameId, pseudo);
        GameStateDTO dto = GameStateDTO.from(masked, pseudo, disconnectedPlayers(gameId), sequence);
        messagingTemplate.convertAndSend(WsDestinations.gameState(gameId, pseudo),
                GameStateMessage.state(dto, clientRequestId, List.of()));
    }

    /** Pousse l'état courant aux deux joueurs (reconnexion, fin de minuteur…). */
    public void pushStates(String gameId, String clientRequestId, List<GameEvent> producedEvents) {
        GameState internal = gameService.getGameStateInternal(gameId);
        Set<String> disconnected = disconnectedPlayers(gameId);
        long sequence = sequenceOf(gameId).incrementAndGet();
        List<GameLogEntryDTO> newEvents = mapNewEvents(internal, producedEvents);
        for (Player player : internal.getPlayers()) {
            GameState masked = gameService.getGameState(gameId, player.getId());
            GameStateDTO dto = GameStateDTO.from(masked, player.getId(), disconnected, sequence);
            messagingTemplate.convertAndSend(
                    WsDestinations.gameState(gameId, player.getId()),
                    GameStateMessage.state(dto, clientRequestId, newEvents));
        }
    }

    public void notice(GameNotice notice) {
        messagingTemplate.convertAndSend(WsDestinations.game(notice.gameId()), notice);
    }

    public void error(String pseudo, WsError error) {
        log.debug("Erreur STOMP pour {} : {} ({})", pseudo, error.code(), error.message());
        messagingTemplate.convertAndSendToUser(pseudo, WsDestinations.ERRORS_QUEUE, error);
    }

    /** Retire les compteurs d'une partie terminée (nettoyage mémoire). */
    public void dispose(String gameId) {
        sequences.remove(gameId);
    }

    private AtomicLong sequenceOf(String gameId) {
        return sequences.computeIfAbsent(gameId, id -> new AtomicLong(0));
    }

    private Set<String> disconnectedPlayers(String gameId) {
        GamePresenceService presence = presenceProvider.getIfAvailable();
        return presence == null ? Set.of() : presence.disconnectedIn(gameId);
    }

    private List<GameLogEntryDTO> mapNewEvents(GameState internal, List<GameEvent> produced) {
        if (produced == null || produced.isEmpty()) {
            return List.of();
        }
        List<GameEvent> logEvents = internal.getEventLog();
        int start = logEvents.size() - produced.size();
        if (start < 0) {
            start = 0;
        }
        List<GameLogEntryDTO> entries = new ArrayList<>(produced.size());
        for (int i = 0; i < produced.size() && start + i < logEvents.size(); i++) {
            entries.add(GameLogEntryDTO.from(start + i, logEvents.get(start + i)));
        }
        return List.copyOf(entries);
    }
}
