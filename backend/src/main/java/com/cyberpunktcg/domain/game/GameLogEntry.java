package com.cyberpunktcg.domain.game;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Une ligne du journal de partie « diagnostic » ({@link GameLog}).
 *
 * <p>Différent du journal d'événements {@link GameEvent} :</p>
 * <ul>
 *   <li>{@link GameEvent} est le récit public destiné aux joueurs (sans secret,
 *   base du rejeu) ;</li>
 *   <li>{@code GameLogEntry} est le journal de <strong>diagnostic</strong> : il
 *   consigne <em>chaque</em> action, y compris les tentatives refusées
 *   ({@link GameActionResult#ILLEGAL}), avec un contexte chiffré
 *   ({@code details}) et l'horodatage. Il n'est exposé que par le panneau de
 *   debug et l'endpoint {@code /api/debug}.</li>
 * </ul>
 *
 * <p>Objets immuables : une entrée consignée n'est jamais modifiée.</p>
 */
public class GameLogEntry {

    /** Index global dans la partie (1 = première ligne consignée). */
    private final int index;
    private final Instant timestamp;
    private final int turnNumber;
    private final Phase phase;
    private final String playerId;
    private final String actionType;
    private final String description;
    private final GameActionResult result;
    private final Map<String, Object> details;

    public GameLogEntry(int index, Instant timestamp, int turnNumber, Phase phase, String playerId,
                        String actionType, String description, GameActionResult result,
                        Map<String, Object> details) {
        if (timestamp == null) {
            throw new IllegalArgumentException("L'horodatage est obligatoire");
        }
        if (phase == null) {
            throw new IllegalArgumentException("La phase est obligatoire");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("Le type d'action est obligatoire");
        }
        if (description == null) {
            throw new IllegalArgumentException("La description est obligatoire");
        }
        if (result == null) {
            throw new IllegalArgumentException("Le résultat est obligatoire");
        }
        this.index = index;
        this.timestamp = timestamp;
        this.turnNumber = turnNumber;
        this.phase = phase;
        this.playerId = playerId;
        this.actionType = actionType;
        this.description = description;
        this.result = result;
        this.details = details == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(details));
    }

    public int getIndex() {
        return index;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public Phase getPhase() {
        return phase;
    }

    /** Joueur à l'origine de l'action ({@code null} pour une ligne système). */
    public String getPlayerId() {
        return playerId;
    }

    /** Type d'action technique ({@code PLAY_CARD}, {@code ATTACK}, {@code VICTORY}…). */
    public String getActionType() {
        return actionType;
    }

    public String getDescription() {
        return description;
    }

    public GameActionResult getResult() {
        return result;
    }

    /** Contexte JSON-compatible (coûts, cibles, ressources) — jamais {@code null}. */
    public Map<String, Object> getDetails() {
        return details;
    }

    @Override
    public String toString() {
        return "#" + index + " [" + phase + "] " + actionType + " (" + result + ") " + description;
    }
}
