package com.cyberpunktcg.ws;

/**
 * Constantes des destinations STOMP, partagées entre le serveur et la doc.
 *
 * <p>Conventions :
 * <ul>
 *   <li>les clients envoient sur {@code /app/**} ;</li>
 *   <li>les annonces publiques passent par {@code /topic/**} ;</li>
 *   <li>les messages privés (erreurs, réponses ciblées) passent par
 *       {@code /user/queue/**} (préfixe utilisateur géré par Spring).</li>
 * </ul>
 */
public final class WsDestinations {

    public static final String ENDPOINT = "/ws";
    public static final String APP_PREFIX = "/app";
    public static final String TOPIC_PREFIX = "/topic";
    public static final String USER_PREFIX = "/user";

    /** Liste publique des salons ouverts. */
    public static final String ROOMS_TOPIC = "/topic/rooms";
    /** État d'un salon précis. */
    public static final String LOBBY_TOPIC_TEMPLATE = "/topic/lobby/%s";
    /** Notifications publiques d'une partie (start, game over, présence…). */
    public static final String GAME_TOPIC_TEMPLATE = "/topic/game/%s";
    /** États masqués d'une partie pour UN joueur (dernier segment = pseudo). */
    public static final String GAME_STATE_TOPIC_TEMPLATE = "/topic/game/%s/%s";

    /** Files privées (chemins relatifs pour convertAndSendToUser). */
    public static final String ERRORS_QUEUE = "/queue/errors";
    public static final String LOBBY_QUEUE = "/queue/lobby";
    public static final String ROOMS_QUEUE = "/queue/rooms";

    private WsDestinations() {
    }

    public static String lobby(String roomCode) {
        return LOBBY_TOPIC_TEMPLATE.formatted(roomCode);
    }

    public static String game(String gameId) {
        return GAME_TOPIC_TEMPLATE.formatted(gameId);
    }

    public static String gameState(String gameId, String pseudo) {
        return GAME_STATE_TOPIC_TEMPLATE.formatted(gameId, pseudo);
    }
}
