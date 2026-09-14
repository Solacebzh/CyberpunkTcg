package com.cyberpunktcg.ws;

import com.cyberpunktcg.api.dto.ws.GameNotice;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.lobby.LobbyService;
import com.cyberpunktcg.lobby.Room;
import com.cyberpunktcg.lobby.RoomStatus;
import com.cyberpunktcg.service.GameService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Suit les sessions STOMP de chaque pseudo et gère le délai de grâce de
 * reconnexion : à la déconnexion d'un joueur en partie, un compte à rebours
 * de {@code app.game.disconnect-grace} (120 s par défaut) est lancé ;
 * sans reconnexion, le joueur perd par forfait.
 *
 * <p>Plusieurs sessions simultanées d'un même pseudo sont tolérées (onglets,
 * reconnexion en chevauchement) : le compte à rebours ne démarre que lorsque
 * la DERNIÈRE session se ferme.</p>
 */
@Service
public class GamePresenceService {

    private static final Logger log = LoggerFactory.getLogger(GamePresenceService.class);

    private final Map<String, Set<String>> sessionsByPseudo = new ConcurrentHashMap<>();
    private final Map<String, String> pseudoBySession = new ConcurrentHashMap<>();
    /** Clé {@code gameId|pseudo} → minuteur de forfait en cours. */
    private final Map<String, ScheduledFuture<?>> countdowns = new ConcurrentHashMap<>();

    private final Duration graceDuration;
    private final ScheduledExecutorService scheduler;
    private final GameService gameService;
    private final LobbyService lobbyService;
    private final GameBroadcaster broadcaster;
    private final LobbyBroadcaster lobbyBroadcaster;

    public GamePresenceService(
            @Value("${app.game.disconnect-grace:PT120S}") Duration graceDuration,
            GameService gameService,
            LobbyService lobbyService,
            GameBroadcaster broadcaster,
            LobbyBroadcaster lobbyBroadcaster) {
        this.graceDuration = graceDuration;
        this.gameService = gameService;
        this.lobbyService = lobbyService;
        this.broadcaster = broadcaster;
        this.lobbyBroadcaster = lobbyBroadcaster;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ws-disconnect-grace");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Enregistre une session STOMP nouvellement connectée et annule tout forfait en cours. */
    public void onConnected(String pseudo, String sessionId) {
        pseudoBySession.put(sessionId, pseudo);
        Set<String> sessions = sessionsByPseudo.computeIfAbsent(pseudo, key -> ConcurrentHashMap.newKeySet());
        boolean wasOffline = sessions.isEmpty();
        sessions.add(sessionId);

        if (!wasOffline) {
            return;
        }
        Optional<Room> roomOpt = lobbyService.findByPlayer(pseudo);
        if (roomOpt.isEmpty()) {
            return;
        }
        Room room = roomOpt.get();
        if (room.getStatus() == RoomStatus.PLAYING && room.getGameId() != null) {
            cancelCountdown(room.getGameId(), pseudo);
            log.info("Reconnexion de {} dans la partie {}", pseudo, room.getGameId());
            broadcaster.notice(GameNotice.playerReconnected(room.getGameId(), pseudo));
            broadcaster.pushStates(room.getGameId(), null, List.of());
        } else if (room.getStatus() == RoomStatus.WAITING) {
            lobbyBroadcaster.sendRoomTo(pseudo, room);
            lobbyBroadcaster.broadcastRoomList();
        }
    }

    /** Retire une session fermée ; déclenche le départ du salon ou le minuteur de forfait. */
    public void onDisconnected(String sessionId) {
        String pseudo = pseudoBySession.remove(sessionId);
        if (pseudo == null) {
            return;
        }
        Set<String> sessions = sessionsByPseudo.get(pseudo);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (!sessions.isEmpty()) {
                return;
            }
        }
        Optional<Room> roomOpt = lobbyService.findByPlayer(pseudo);
        if (roomOpt.isEmpty()) {
            sessionsByPseudo.remove(pseudo);
            return;
        }
        Room room = roomOpt.get();
        if (room.getStatus() == RoomStatus.WAITING) {
            handleWaitingRoomDisconnect(room, pseudo);
            sessionsByPseudo.remove(pseudo);
        } else if (room.getStatus() == RoomStatus.PLAYING && room.getGameId() != null) {
            scheduleForfeitCountdown(room.getGameId(), pseudo);
        }
    }

    private void handleWaitingRoomDisconnect(Room room, String pseudo) {
        Optional<Room> remaining = lobbyService.leaveRoom(pseudo, room.getCode());
        if (remaining.isPresent() && remaining.get().getStatus() == RoomStatus.WAITING) {
            lobbyBroadcaster.broadcastRoom(remaining.get());
            log.info("{} déconnecté, salon {} toujours ouvert", pseudo, room.getCode());
        } else {
            log.info("Salon {} fermé après déconnexion de {}", room.getCode(), pseudo);
        }
        lobbyBroadcaster.broadcastRoomList();
    }

    private void scheduleForfeitCountdown(String gameId, String pseudo) {
        String key = countdownKey(gameId, pseudo);
        ScheduledFuture<?> task = scheduler.schedule(() -> forfeitIfStillOffline(gameId, pseudo),
                graceDuration.toMillis(), TimeUnit.MILLISECONDS);
        countdowns.put(key, task);
        log.info("{} déconnecté de la partie {} : forfait dans {} s",
                pseudo, gameId, graceDuration.toSeconds());
        broadcaster.notice(GameNotice.playerDisconnected(gameId, pseudo, (int) graceDuration.toSeconds()));
        broadcaster.pushStates(gameId, null, List.of());
    }

    private void cancelCountdown(String gameId, String pseudo) {
        ScheduledFuture<?> task = countdowns.remove(countdownKey(gameId, pseudo));
        if (task != null) {
            task.cancel(false);
        }
    }

    private void forfeitIfStillOffline(String gameId, String pseudo) {
        try {
            Set<String> sessions = sessionsByPseudo.getOrDefault(pseudo, Set.of());
            if (!sessions.isEmpty()) {
                return;
            }
            GameState state = gameService.getGameStateInternal(gameId);
            if (state.isGameOver()) {
                return;
            }
            GameEvent event = gameService.concede(gameId, pseudo, "Forfait déconnexion");
            log.info("Forfait pour déconnexion de {} dans la partie {}", pseudo, gameId);
            broadcaster.pushStates(gameId, null, event == null ? List.of() : List.of(event));
            broadcaster.notice(GameNotice.gameOver(gameId, state.getWinnerId(), state.getEndReason()));
            lobbyService.closeByGameId(gameId);
            broadcaster.dispose(gameId);
            sessionsByPseudo.remove(pseudo);
        } catch (RuntimeException error) {
            log.warn("Échec du forfait pour déconnexion (partie {} / {}) : {}",
                    gameId, pseudo, error.getMessage());
        } finally {
            countdowns.remove(countdownKey(gameId, pseudo));
        }
    }

    /** Joueurs actuellement sans aucune session dans la partie (pour l'DTO connecté=false). */
    public Set<String> disconnectedIn(String gameId) {
        Set<String> disconnected = new HashSet<>();
        try {
            GameState state = gameService.getGameStateInternal(gameId);
            for (Player player : state.getPlayers()) {
                if (sessionsByPseudo.getOrDefault(player.getId(), Set.of()).isEmpty()) {
                    disconnected.add(player.getId());
                }
            }
        } catch (RuntimeException missing) {
            log.debug("Partie {} introuvable pour le calcul de présence", gameId);
        }
        return disconnected;
    }

    private String countdownKey(String gameId, String pseudo) {
        return gameId + '|' + pseudo;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
