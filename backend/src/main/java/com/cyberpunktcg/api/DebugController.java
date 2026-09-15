package com.cyberpunktcg.api;

import com.cyberpunktcg.api.dto.debug.DebugGameStateDTO;
import com.cyberpunktcg.api.dto.debug.DebugPlayerStateDTO;
import com.cyberpunktcg.api.dto.debug.DebugReactionWindowDTO;
import com.cyberpunktcg.api.dto.debug.ForcePhaseRequest;
import com.cyberpunktcg.api.dto.ws.GameActionLogDTO;
import com.cyberpunktcg.api.dto.ws.GameLogEntryDTO;
import com.cyberpunktcg.domain.game.GameLogEntry;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.service.GameService;
import com.cyberpunktcg.ws.GameBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint de debug des parties (feature 6.5) — <strong>actif uniquement sous les
 * profils {@code test} et {@code dev}</strong>.
 *
 * <p>Contrairement aux canaux de jeu, ces routes ne masquent rien : elles
 * exposent les mains, les pioches et les journaux complets pour diagnostiquer les
 * règles. Elles ne doivent jamais être activées en production
 * ({@code @Profile({"test","dev"})}).</p>
 *
 * <ul>
 *   <li>{@code GET  /api/debug/games} : parties en mémoire (aide au repérage) ;</li>
 *   <li>{@code GET  /api/debug/game/{gameId}} : état complet des deux joueurs ;</li>
 *   <li>{@code GET  /api/debug/game/{gameId}/player/{playerId}} : état détaillé d'un joueur ;</li>
 *   <li>{@code POST /api/debug/game/{gameId}/force-phase} : force la phase courante.</li>
 * </ul>
 *
 * <p>Voir {@code docs/DEBUG-GUIDE.md} (recettes de diagnostic) et
 * {@code docs/RULE-ENGINE.md} §11.</p>
 */
@RestController
@RequestMapping("/api/debug")
@Profile({"test", "dev"})
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    /** Nombre d'entrées du journal de diagnostic renvoyées par défaut. */
    public static final int LOG_TAIL = 20;
    /** Nombre maximal accepté pour {@code ?logs=}. */
    private static final int LOG_TAIL_MAX = 200;

    private final GameService gameService;
    private final GameBroadcaster broadcaster;

    public DebugController(GameService gameService, GameBroadcaster broadcaster) {
        this.gameService = gameService;
        this.broadcaster = broadcaster;
    }

    /** Parties actuellement en mémoire (utile quand on ne connaît pas le gameId). */
    @GetMapping("/games")
    public Map<String, Object> games() {
        List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
        for (String gameId : gameService.listGameIds()) {
            try {
                GameState state = gameService.getGameStateInternal(gameId);
                Map<String, Object> summary = new LinkedHashMap<String, Object>();
                summary.put("gameId", gameId);
                summary.put("turn", state.getTurn().getNumber());
                summary.put("phase", state.getPhase().name());
                summary.put("activePlayerId", state.getTurn().getActivePlayerId());
                summary.put("players", state.getPlayers().stream().map(Player::getId).toList());
                summary.put("gameOver", state.isGameOver());
                summary.put("winnerId", state.getWinnerId());
                summaries.add(summary);
            } catch (ResponseStatusException error) {
                // Partie disparue entre-temps : ignorée.
                log.debug("Partie {} introuvable pendant le listing de debug", gameId);
            }
        }
        return Map.of("count", summaries.size(), "games", summaries);
    }

    /**
     * État complet et non masqué d'une partie (les deux joueurs).
     *
     * @param logs nombre d'entrées du journal de diagnostic à renvoyer
     *             (défaut {@value #LOG_TAIL}, maximum {@value #LOG_TAIL_MAX})
     */
    @GetMapping("/game/{gameId}")
    public DebugGameStateDTO game(@PathVariable String gameId,
                                  @RequestParam(name = "logs", required = false) Integer logs) {
        return snapshot(gameService.getGameStateInternal(gameId), clampLogTail(logs));
    }

    /** État détaillé d'un joueur précis (mains et pioches visibles). */
    @GetMapping("/game/{gameId}/player/{playerId}")
    public DebugPlayerStateDTO player(@PathVariable String gameId, @PathVariable String playerId) {
        GameState state = gameService.getGameStateInternal(gameId);
        if (!state.hasPlayer(playerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Joueur inconnu dans cette partie : " + playerId);
        }
        return DebugPlayerStateDTO.from(state.getPlayer(playerId));
    }

    /**
     * Force la phase courante (test d'une règle sans rejouer la partie) :
     * {@code {"phase": "COMBAT"}}. La manipulation est journalisée dans le
     * journal de diagnostic et diffusée sur {@code /topic/game/{gameId}/log}.
     */
    @PostMapping("/game/{gameId}/force-phase")
    public DebugGameStateDTO forcePhase(@PathVariable String gameId,
                                        @RequestBody(required = false) ForcePhaseRequest body) {
        if (body == null || body.phase() == null || body.phase().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Corps attendu : {\"phase\": \"DRAW|MAIN|COMBAT|END\"}");
        }
        Phase phase;
        try {
            phase = Phase.valueOf(body.phase().trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Phase inconnue : " + body.phase() + " (attendu DRAW, MAIN, COMBAT ou END)");
        }
        GameState state = gameService.forcePhase(gameId, phase, body.playerId());
        broadcaster.pushLog(gameId);
        return snapshot(state, LOG_TAIL);
    }

    // ------------------------------------------------------------------
    // Fabriques
    // ------------------------------------------------------------------

    private DebugGameStateDTO snapshot(GameState state, int logTail) {
        List<GameActionLogDTO> logs = new ArrayList<GameActionLogDTO>();
        for (GameLogEntry entry : gameService.getGameLog(state.getGameId(), logTail)) {
            logs.add(GameActionLogDTO.from(entry));
        }
        List<GameLogEntryDTO> events = new ArrayList<GameLogEntryDTO>();
        List<com.cyberpunktcg.domain.game.GameEvent> eventLog = state.getEventLog();
        for (int i = 0; i < eventLog.size(); i++) {
            events.add(GameLogEntryDTO.from(i, eventLog.get(i)));
        }
        return new DebugGameStateDTO(
                state.getGameId(),
                state.getTurn().getNumber(),
                state.getPhase().name(),
                state.getTurn().getActivePlayerId(),
                state.isGameOver(),
                state.getWinnerId(),
                state.getEndReason(),
                state.getSeed(),
                state.getCreatedAt(),
                DebugReactionWindowDTO.from(state.getReactionWindow()),
                state.getPlayers().stream().map(DebugPlayerStateDTO::from).toList(),
                List.copyOf(events),
                List.copyOf(logs));
    }

    /** Borne le paramètre {@code logs} (utilisé par la surcharge de debug). */
    static int clampLogTail(Integer requested) {
        if (requested == null || requested <= 0) {
            return LOG_TAIL;
        }
        return Math.min(requested, LOG_TAIL_MAX);
    }
}
