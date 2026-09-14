package com.cyberpunktcg.api.dto.ws;

import java.util.List;

/**
 * Enveloppe diffusée sur la destination privée d'état
 * ({@code /topic/game/{gameId}/{pseudo}}).
 *
 * @param type            toujours {@code "STATE"}
 * @param gameId          partie concernée
 * @param clientRequestId renvoyé si la poussée fait suite à une action du client
 * @param state           état complet masqué pour ce destinataire
 * @param newEvents       événements produits par la dernière action (animations)
 */
public record GameStateMessage(
        String type,
        String gameId,
        String clientRequestId,
        GameStateDTO state,
        List<GameLogEntryDTO> newEvents
) {
    public static GameStateMessage state(GameStateDTO state, String clientRequestId, List<GameLogEntryDTO> newEvents) {
        return new GameStateMessage("STATE", state.gameId(), clientRequestId, state, newEvents);
    }
}
