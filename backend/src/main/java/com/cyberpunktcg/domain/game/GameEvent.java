package com.cyberpunktcg.domain.game;

/**
 * Événement immuable du journal de partie.
 */
public class GameEvent {

    private final GameEventType type;
    private final String playerId;
    private final String description;

    public GameEvent(GameEventType type, String playerId, String description) {
        if (type == null) {
            throw new IllegalArgumentException("Le type d'événement est obligatoire");
        }
        if (description == null) {
            throw new IllegalArgumentException("La description d'événement est obligatoire");
        }
        this.type = type;
        this.playerId = playerId;
        this.description = description;
    }

    public GameEventType getType() {
        return type;
    }

    /** Joueur à l'origine de l'événement (peut être {@code null} pour le système). */
    public String getPlayerId() {
        return playerId;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "GameEvent{type=" + type + ", playerId='" + playerId + "', description='" + description + "'}";
    }
}
