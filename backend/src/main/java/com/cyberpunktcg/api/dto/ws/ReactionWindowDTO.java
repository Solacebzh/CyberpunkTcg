package com.cyberpunktcg.api.dto.ws;

/**
 * Fenêtre de réaction ouverte (attaque en cours, carte QUICK…).
 * {@code null} lorsqu'aucune fenêtre n'est ouverte.
 */
public record ReactionWindowDTO(
        String kind,
        String defendingPlayerId,
        String attackerInstanceId
) {
}
