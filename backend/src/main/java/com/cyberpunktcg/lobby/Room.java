package com.cyberpunktcg.lobby;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un salon d'attente : jusqu'à deux joueurs, triés par siège (0 = hôte,
 * 1 = invité). Toutes les mutations sont synchronisées pour encaisser les
 * arrivées/départs concurrents sur sessions WebSocket.
 */
public class Room {

    /** Vue immuable d'un siège, utilisée par les DTO. */
    public record SeatView(String pseudo, int seat, int deckCardCount) {
    }

    private record Seat(String pseudo, List<String> deckCardIds, Instant joinedAt) {
    }

    private final String code;
    private final String name;
    private final Instant createdAt = Instant.now();
    private final Map<String, Seat> seats = new LinkedHashMap<>();
    private volatile RoomStatus status = RoomStatus.WAITING;
    private volatile String gameId;

    public Room(String code, String name, String hostPseudo, List<String> hostDeck) {
        this.code = code;
        this.name = name;
        this.seats.put(hostPseudo, new Seat(hostPseudo, List.copyOf(hostDeck), Instant.now()));
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public String getGameId() {
        return gameId;
    }

    public synchronized String getHostPseudo() {
        return seats.isEmpty() ? null : seats.keySet().iterator().next();
    }

    public synchronized boolean isWaiting() {
        return status == RoomStatus.WAITING;
    }

    public synchronized boolean isFull() {
        return seats.size() >= 2;
    }

    public synchronized boolean contains(String pseudo) {
        return seats.containsKey(pseudo);
    }

    /** Ajoute le second joueur ; renvoie IllegalStateException si plein/déjà présent. */
    public synchronized void addGuest(String pseudo, List<String> deckCardIds) {
        if (status != RoomStatus.WAITING) {
            throw new IllegalStateException("Le salon n'est plus rejoignable");
        }
        if (seats.containsKey(pseudo)) {
            throw new IllegalStateException("Pseudo déjà assis dans ce salon");
        }
        if (seats.size() >= 2) {
            throw new IllegalStateException("Le salon est plein");
        }
        seats.put(pseudo, new Seat(pseudo, List.copyOf(deckCardIds), Instant.now()));
    }

    /** Retire un joueur ; renvoie true si le salon devient vide. */
    public synchronized boolean remove(String pseudo) {
        seats.remove(pseudo);
        return seats.isEmpty();
    }

    /** Fige le salon en partie démarrée et mémorise l'identifiant de partie. */
    public synchronized void markStarted(String startedGameId) {
        this.status = RoomStatus.PLAYING;
        this.gameId = startedGameId;
    }

    public synchronized void close() {
        this.status = RoomStatus.CLOSED;
    }

    /** Joueurs dans l'ordre des sièges (hôte en premier). */
    public synchronized List<SeatView> seatView() {
        List<SeatView> views = new ArrayList<>(seats.size());
        int seat = 0;
        for (Seat current : seats.values()) {
            views.add(new SeatView(current.pseudo(), seat, current.deckCardIds().size()));
            seat++;
        }
        return List.copyOf(views);
    }

    /** Identifiants de cartes du deck d'un joueur. */
    public synchronized List<String> deckOf(String pseudo) {
        Seat seat = seats.get(pseudo);
        return seat == null ? List.of() : seat.deckCardIds();
    }
}
