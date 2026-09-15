package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.game.GameLogEntry;

import java.time.Instant;
import java.util.Map;

/**
 * Une entrée du <strong>journal de diagnostic</strong> (feature 6.5), telle que
 * vue par le client et le panneau de debug.
 *
 * <p>Différente de {@link GameLogEntryDTO} (qui projette les
 * {@link com.cyberpunktcg.domain.game.GameEvent} publics) : cette entrée porte le
 * verdict ({@code SUCCESS}, {@code FAILED}, {@code ILLEGAL}, {@code INFO}), la
 * phase et le tour, et un contexte chiffré ({@code details}).</p>
 *
 * @param index       ordre global dans la partie (1, 2, 3…)
 * @param timestamp   horodatage serveur
 * @param turnNumber  tour pendant lequel l'action a eu lieu
 * @param phase       phase pendant laquelle l'action a eu lieu
 * @param playerId    joueur à l'origine de l'action (absent = système)
 * @param actionType  type technique (PLAY_CARD, ATTACK, SELL_CARD, VICTORY…)
 * @param description libellé lisible
 * @param result      verdict de l'action
 * @param details     contexte JSON-compatible (coûts, cibles, ressources)
 */
public record GameActionLogDTO(
        int index,
        Instant timestamp,
        int turnNumber,
        String phase,
        String playerId,
        String actionType,
        String description,
        String result,
        Map<String, Object> details
) {
    public static GameActionLogDTO from(GameLogEntry entry) {
        return new GameActionLogDTO(
                entry.getIndex(),
                entry.getTimestamp(),
                entry.getTurnNumber(),
                entry.getPhase() == null ? null : entry.getPhase().name(),
                entry.getPlayerId(),
                entry.getActionType(),
                entry.getDescription(),
                entry.getResult() == null ? null : entry.getResult().name(),
                entry.getDetails());
    }
}
