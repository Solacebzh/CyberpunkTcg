package com.cyberpunktcg.api.dto.debug;

/**
 * Corps de {@code POST /api/debug/game/{gameId}/force-phase} : force la phase
 * courante pour tester une règle sans rejouer les phases précédentes.
 *
 * @param phase   phase cible ({@code DRAW}, {@code MAIN}, {@code COMBAT}, {@code END})
 * @param playerId joueur au nom duquel journaliser l'opération (optionnel)
 */
public record ForcePhaseRequest(String phase, String playerId) {
}
