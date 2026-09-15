package com.cyberpunktcg.api.dto.ws;

import java.util.List;

/**
 * Enveloppe diffusée en temps réel sur {@code /topic/game/{gameId}/log}
 * (feature 6.5). Le champ {@code type} vaut toujours {@code "LOG"} : il permet au
 * client de distinguer ce flux des états personnels
 * ({@code GameStateMessage}, {@code type: "STATE"}) qui partagent le préfixe
 * {@code /topic/game/{gameId}/…}.
 *
 * @param type    toujours {@code "LOG"}
 * @param gameId  partie concernée
 * @param entries nouvelles entrées du journal de diagnostic (ordre chronologique)
 */
public record GameLogMessage(
        String type,
        String gameId,
        List<GameActionLogDTO> entries
) {
    public static GameLogMessage of(String gameId, List<GameActionLogDTO> entries) {
        return new GameLogMessage("LOG", gameId, entries);
    }
}
