package com.cyberpunktcg.api;

import com.cyberpunktcg.api.dto.ws.GameCommandDTO;
import com.cyberpunktcg.api.dto.ws.WsError;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.GameCommand;
import com.cyberpunktcg.lobby.LobbyService;
import com.cyberpunktcg.service.ActionCommandFactory;
import com.cyberpunktcg.service.GameService;
import com.cyberpunktcg.ws.GameBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

/**
 * Endpoints STOMP d'une partie (préfixe {@code /app}).
 *
 * <p>Le serveur fait autorité : chaque commande est validée par le moteur,
 * puis un état complet masqué est renvoyé aux deux joueurs. Les erreurs de
 * règles ne reviennent qu'au joueur fautif, sur
 * {@code /user/queue/errors}.</p>
 */
@Controller
public class GameWsController {

    private static final Logger log = LoggerFactory.getLogger(GameWsController.class);

    private final GameService gameService;
    private final LobbyService lobbyService;
    private final ActionCommandFactory commandFactory;
    private final GameBroadcaster broadcaster;

    public GameWsController(GameService gameService,
                            LobbyService lobbyService,
                            ActionCommandFactory commandFactory,
                            GameBroadcaster broadcaster) {
        this.gameService = gameService;
        this.lobbyService = lobbyService;
        this.commandFactory = commandFactory;
        this.broadcaster = broadcaster;
    }

    /** Exécute une action de jeu (PLAY_CARD, ATTACK, SELL_CARD, END_TURN, CONCEDE…). */
    @MessageMapping("/game/{gameId}/action")
    public void action(@DestinationVariable String gameId,
                       @Payload GameCommandDTO payload,
                       StompHeaderAccessor accessor) {
        String pseudo = currentPseudo(accessor);
        String destination = "/app/game/" + gameId + "/action";
        if (pseudo == null) {
            return;
        }
        String requestId = payload == null ? null : payload.clientRequestId();
        try {
            if (payload == null) {
                throw new GameRuleException("Commande vide (payload JSON requis)");
            }
            if (ActionCommandFactory.CONCEDE.equalsIgnoreCase(payload.action())) {
                handleConcede(gameId, pseudo, requestId);
                return;
            }
            GameCommand command = commandFactory.build(payload, pseudo);
            List<GameEvent> events = gameService.executeCommand(gameId, command);
            broadcaster.afterAction(gameId, requestId, events);
            closeRoomIfFinished(gameId);
        } catch (GameRuleException error) {
            sendError(pseudo, "ILLEGAL_ACTION", error.getMessage(), destination, gameId, requestId);
        } catch (ResponseStatusException error) {
            String code = error.getStatusCode() == HttpStatus.NOT_FOUND ? "GAME_NOT_FOUND" : "ACTION_REJECTED";
            sendError(pseudo, code, reasonOf(error), destination, gameId, requestId);
        } catch (IllegalArgumentException error) {
            sendError(pseudo, "BAD_REQUEST", error.getMessage(), destination, gameId, requestId);
        } catch (RuntimeException error) {
            log.error("Action {} en échec sur la partie {}", payload == null ? "?" : payload.action(), gameId, error);
            sendError(pseudo, "INTERNAL_ERROR",
                    "Erreur interne lors du traitement de l'action", destination, gameId, requestId);
        }
    }

    /** Renvoie immédiatement l'état courant au demandeur (après (re)connexion ou souscription). */
    @MessageMapping("/game/{gameId}/resync")
    public void resync(@DestinationVariable String gameId, StompHeaderAccessor accessor) {
        String pseudo = currentPseudo(accessor);
        String destination = "/app/game/" + gameId + "/resync";
        if (pseudo == null) {
            return;
        }
        try {
            broadcaster.sendStateToPlayer(gameId, pseudo, null);
        } catch (ResponseStatusException error) {
            String code = error.getStatusCode() == HttpStatus.NOT_FOUND ? "GAME_NOT_FOUND" : "ACTION_REJECTED";
            sendError(pseudo, code, reasonOf(error), destination, gameId, null);
        }
    }

    private void handleConcede(String gameId, String pseudo, String requestId) {
        GameEvent event = gameService.concede(gameId, pseudo, "Abandon");
        broadcaster.afterAction(gameId, requestId, event == null ? List.of() : List.of(event));
        closeRoomIfFinished(gameId);
    }

    /** Libère les sièges du lobby et les séquences une fois la partie terminée. */
    private void closeRoomIfFinished(String gameId) {
        if (gameService.getGameStateInternal(gameId).isGameOver()) {
            lobbyService.closeByGameId(gameId);
            broadcaster.dispose(gameId);
        }
    }

    private String currentPseudo(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            log.warn("Commande de jeu sans identité STOMP rejetée");
            return null;
        }
        return principal.getName();
    }

    private void sendError(String pseudo, String code, String message,
                           String destination, String gameId, String requestId) {
        broadcaster.error(pseudo, WsError.of(code, message, destination, gameId, requestId));
    }

    private String reasonOf(ResponseStatusException error) {
        return error.getReason() != null ? error.getReason() : error.getMessage();
    }
}
