package com.cyberpunktcg.domain.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal de diagnostic d'une partie (feature 6.5).
 *
 * <p>Journal chronologique <strong>borné</strong> : seules les
 * {@link #MAX_ENTRIES} dernières entrées sont conservées afin de garder une
 * empreinte mémoire constante sur une partie longue (les plus anciennes sont
 * comptées dans {@link #getDroppedCount()} mais ne sont plus exposées).</p>
 *
 * <p>Le journal est indépendant du journal d'événements publics
 * ({@link GameEvent}) : il enregistre aussi les actions refusées, ce que le
 * journal public ne fait jamais.</p>
 */
public class GameLog {

    /** Nombre maximal d'entrées conservées par partie. */
    public static final int MAX_ENTRIES = 200;

    private final List<GameLogEntry> entries;
    private int nextIndex;
    private int dropped;

    public GameLog() {
        this.entries = new ArrayList<GameLogEntry>();
        this.nextIndex = 1;
        this.dropped = 0;
    }

    private GameLog(List<GameLogEntry> entries, int nextIndex, int dropped) {
        this.entries = entries;
        this.nextIndex = nextIndex;
        this.dropped = dropped;
    }

    /**
     * Ajoute une ligne au journal (en évacuant la plus ancienne si nécessaire).
     *
     * @return l'entrée créée et consignée
     */
    public GameLogEntry append(int turnNumber, Phase phase, String playerId, String actionType,
                               String description, GameActionResult result, Map<String, Object> details) {
        GameLogEntry entry = new GameLogEntry(nextIndex, Instant.now(), turnNumber, phase, playerId,
                actionType, description, result, details);
        append(entry);
        return entry;
    }

    /** Ajoute une entrée déjà construite (respecte la borne {@link #MAX_ENTRIES}). */
    public void append(GameLogEntry entry) {
        entries.add(entry);
        nextIndex = Math.max(nextIndex, entry.getIndex()) + 1;
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
            dropped++;
        }
    }

    /** Entrées conservées, dans l'ordre chronologique (lecture seule). */
    public List<GameLogEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** Les {@code count} dernières entrées (au plus), dans l'ordre chronologique. */
    public List<GameLogEntry> recent(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        int from = Math.max(0, entries.size() - count);
        return Collections.unmodifiableList(new ArrayList<GameLogEntry>(entries.subList(from, entries.size())));
    }

    /** Entrées dont l'index est strictement supérieur à {@code index} (diffusion incrémentale). */
    public List<GameLogEntry> since(int index) {
        List<GameLogEntry> result = new ArrayList<GameLogEntry>();
        for (GameLogEntry entry : entries) {
            if (entry.getIndex() > index) {
                result.add(entry);
            }
        }
        return result;
    }

    public int size() {
        return entries.size();
    }

    /** Nombre d'entrées évacuées (partie longue) — informatif. */
    public int getDroppedCount() {
        return dropped;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Copie détachée (vues masquées : les entrées sont immuables). */
    public GameLog copy() {
        return new GameLog(new ArrayList<GameLogEntry>(entries), nextIndex, dropped);
    }

    /** Index de la dernière entrée consignée ({@code 0} si journal vide). */
    public int lastIndex() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).getIndex();
    }

    /** Détails null-safe pour les fabriques d'entrées. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    @Override
    public String toString() {
        return "GameLog{entries=" + entries.size() + ", dropped=" + dropped + '}';
    }
}
