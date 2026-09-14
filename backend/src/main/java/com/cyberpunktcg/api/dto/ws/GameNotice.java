package com.cyberpunktcg.api.dto.ws;

/**
 * Notification PUBLIQUE diffusée sur {@code /topic/game/{gameId}}.
 * Elle ne contient jamais de carte secrète : uniquement des métadonnées
 * (début/fin de partie, connexion/déconnexion d'un joueur).
 *
 * @param type   GAME_STARTED, GAME_OVER, PLAYER_DISCONNECTED, PLAYER_RECONNECTED
 * @param gameId partie concernée
 * @param playerId joueur concerné (déconnexion/reconnexion), null sinon
 * @param winnerId vainqueur (GAME_OVER uniquement)
 * @param endReason raison de fin (GAME_OVER uniquement)
 * @param reconnectDeadInSeconds délai de reconnexion avant forfait (déconnexion)
 */
public record GameNotice(
        String type,
        String gameId,
        String playerId,
        String winnerId,
        String endReason,
        Integer reconnectDeadInSeconds
) {
    public static GameNotice gameStarted(String gameId) {
        return new GameNotice("GAME_STARTED", gameId, null, null, null, null);
    }

    public static GameNotice gameOver(String gameId, String winnerId, String endReason) {
        return new GameNotice("GAME_OVER", gameId, null, winnerId, endReason, null);
    }

    public static GameNotice playerDisconnected(String gameId, String playerId, int graceSeconds) {
        return new GameNotice("PLAYER_DISCONNECTED", gameId, playerId, null, null, graceSeconds);
    }

    public static GameNotice playerReconnected(String gameId, String playerId) {
        return new GameNotice("PLAYER_RECONNECTED", gameId, playerId, null, null, null);
    }
}
