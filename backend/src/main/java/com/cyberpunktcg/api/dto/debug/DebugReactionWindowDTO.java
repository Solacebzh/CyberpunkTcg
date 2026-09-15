package com.cyberpunktcg.api.dto.debug;

import com.cyberpunktcg.domain.game.ReactionWindow;

/**
 * Fenêtre de réaction ouverte (debug) : le défenseur ne peut y jouer que des
 * cartes {@code QUICK}.
 *
 * @param kind                cause de l'ouverture ({@code ATTACK} en V1)
 * @param defendingPlayerId   joueur autorisé à répondre
 * @param attackerInstanceId  exemplaire attaquant concerné
 */
public record DebugReactionWindowDTO(
        String kind,
        String defendingPlayerId,
        String attackerInstanceId
) {
    public static DebugReactionWindowDTO from(ReactionWindow window) {
        if (window == null) {
            return null;
        }
        return new DebugReactionWindowDTO("ATTACK", window.getDefendingPlayerId(),
                window.getAttackerInstanceId());
    }
}
