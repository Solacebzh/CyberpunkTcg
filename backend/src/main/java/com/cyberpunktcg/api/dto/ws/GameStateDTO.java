package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameLogEntry;
import com.cyberpunktcg.domain.game.GameState;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Photographie complète et masquée d'une partie, envoyée à un joueur précis.
 *
 * <p>Le champ {@code yourPlayerId} permet au front de se repérer entre les
 * deux sièges ; {@code connected} de chaque joueur pilote l'affichage du
 * minuteur de reconnexion (120 s avant forfait).</p>
 */
public record GameStateDTO(
        String gameId,
        String phase,
        boolean gameOver,
        String winnerId,
        String endReason,
        String yourPlayerId,
        TurnDTO turn,
        ReactionWindowDTO reactionWindow,
        List<PlayerStateDTO> players,
        List<GameLogEntryDTO> log,
        List<GameActionLogDTO> gameLog,
        long sequence,
        Instant createdAt
) {
    /** Nombre d'entrées de journal de diagnostic embarquées dans chaque état. */
    public static final int DEBUG_LOG_SIZE = 50;

    public static GameStateDTO from(GameState state, String viewerId, Set<String> disconnectedPlayers, long sequence) {
        List<PlayerStateDTO> players = state.getPlayers().stream()
                .map(player -> PlayerStateDTO.from(player, !disconnectedPlayers.contains(player.getId())))
                .toList();
        List<GameLogEntryDTO> log = mapLog(state.getEventLog());
        List<GameActionLogDTO> gameLog = mapActionLog(state);
        ReactionWindowDTO reaction = state.getReactionWindow() == null
                ? null
                : new ReactionWindowDTO(
                        "ATTACK",
                        state.getReactionWindow().getDefendingPlayerId(),
                        state.getReactionWindow().getAttackerInstanceId());
        return new GameStateDTO(
                state.getGameId(),
                state.getPhase().name(),
                state.isGameOver(),
                state.getWinnerId(),
                state.getEndReason(),
                viewerId,
                TurnDTO.from(state),
                reaction,
                players,
                log,
                gameLog,
                sequence,
                state.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    /**
     * Dernières entrées du journal de diagnostic (feature 6.5). Le panneau de
     * debug s'en sert pour afficher les 50 dernières lignes ; le temps réel passe
     * par {@code /topic/game/{gameId}/log}.
     */
    private static List<GameActionLogDTO> mapActionLog(GameState state) {
        List<GameActionLogDTO> entries = new java.util.ArrayList<GameActionLogDTO>();
        for (GameLogEntry entry : state.getGameLog().recent(GameStateDTO.DEBUG_LOG_SIZE)) {
            entries.add(GameActionLogDTO.from(entry));
        }
        return List.copyOf(entries);
    }

    private static List<GameLogEntryDTO> mapLog(List<GameEvent> events) {
        java.util.ArrayList<GameLogEntryDTO> entries = new java.util.ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            entries.add(GameLogEntryDTO.from(i, events.get(i)));
        }
        return List.copyOf(entries);
    }
}
