package com.cyberpunktcg.api.dto.ws;

import java.util.List;
import java.util.UUID;

/**
 * Commande envoyée par un client sur {@code /app/game/{gameId}/action}.
 *
 * <p>L'identité du joueur n'est JAMAIS lue ici : elle vient de la session
 * STOMP (pseudo passé au handshake CONNECT), le serveur fait autorité.
 * Seules les cibles de jeu sont fournies par le client.</p>
 *
 * @param action          type d'action (cf. action list dans WEBSOCKET-PROTOCOL.md)
 * @param instanceId      carte ciblée (PLAY_CARD, ATTACK, ENABLE_UNIT…)
 * @param targetPlayerId  cible joueur (MELEE_ATTACK)
 * @param targetInstanceId carte ciblée (RANGED_ATTACK, ENABLE_UNIT…)
 * @param chosen          option choisie pour une fenêtre de réaction
 * @param revealedLegendIds Legends révélées pour une capacité flip
 * @param cardIds         cartes vendues (SELL_CARDS) ou piochées de sideboard (DRAW_EXTRA)
 * @param dice            valeurs de dés choisies (SET_FIXER_DIE)
 * @param clientRequestId idempotence/debug, renvoyé tel quel dans la réponse
 */
public record GameCommandDTO(
        String action,
        UUID instanceId,
        String targetPlayerId,
        UUID targetInstanceId,
        String chosen,
        List<UUID> revealedLegendIds,
        List<UUID> cardIds,
        List<String> dice,
        String clientRequestId
) {
}
