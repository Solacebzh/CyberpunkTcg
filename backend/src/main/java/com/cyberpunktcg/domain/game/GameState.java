package com.cyberpunktcg.domain.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * État complet d'une partie 1v1.
 *
 * <p>Objet purement Java (aucune dépendance Spring ou JPA) : il vit en mémoire
 * dans {@code GameService} et n'est manipulé que par les commandes du moteur.
 * Les tirages utilisent un {@link Random} initialisé avec la graine de la partie
 * (base du futur rejeu déterministe R10).</p>
 *
 * <p>Les méthodes de pioche / vol / lancer appliquent les effets mécaniques mais
 * ne journalisent pas : ce sont les commandes et le moteur qui ajoutent les
 * {@link GameEvent} (leurs descriptions sont garanties sans secret).</p>
 */
public class GameState {

    private final String gameId;
    private final Map<String, Player> players;
    private Turn turn;
    private ReactionWindow reactionWindow;
    private String winnerId;
    private String endReason;
    private final long seed;
    private final Random random;
    private Instant createdAt;
    private final List<GameEvent> eventLog;

    /**
     * Nouvelle partie 1v1.
     *
     * @param gameId  identifiant unique de la partie
     * @param players exactement 2 joueurs (l'ordre = ordre des sièges ; le premier commence)
     * @param seed    graine des tirages (dés Gig, mélanges futurs)
     */
    public GameState(String gameId, List<Player> players, long seed) {
        if (gameId == null) {
            throw new IllegalArgumentException("L'identifiant de partie est obligatoire");
        }
        if (players == null || players.size() != 2) {
            throw new IllegalArgumentException("Une partie 1v1 exige exactement 2 joueurs");
        }
        this.gameId = gameId;
        this.players = new LinkedHashMap<String, Player>();
        for (Player player : players) {
            this.players.put(player.getId(), player);
        }
        this.turn = new Turn(1, players.get(0).getId(), Phase.MAIN);
        this.reactionWindow = null;
        this.winnerId = null;
        this.endReason = null;
        this.seed = seed;
        this.random = new Random(seed);
        this.createdAt = Instant.now();
        this.eventLog = new ArrayList<GameEvent>();
    }

    /** Constructeur interne des copies (vues masquées : tirages jamais utilisés). */
    private GameState(String gameId, List<Player> players, long seed, Random random,
                      Turn turn, ReactionWindow reactionWindow, String winnerId,
                      String endReason, Instant createdAt, List<GameEvent> eventLog) {
        this.gameId = gameId;
        this.players = new LinkedHashMap<String, Player>();
        for (Player player : players) {
            this.players.put(player.getId(), player);
        }
        this.turn = turn;
        this.reactionWindow = reactionWindow;
        this.winnerId = winnerId;
        this.endReason = endReason;
        this.seed = seed;
        this.random = random;
        this.createdAt = createdAt;
        this.eventLog = eventLog;
    }

    public String getGameId() {
        return gameId;
    }

    /** Joueurs dans l'ordre des sièges (listes modifiables réservées au moteur). */
    public List<Player> getPlayers() {
        return new ArrayList<Player>(players.values());
    }

    public boolean hasPlayer(String playerId) {
        return players.containsKey(playerId);
    }

    public Player getPlayer(String playerId) {
        Player player = players.get(playerId);
        if (player == null) {
            throw new IllegalArgumentException("Joueur inconnu dans cette partie : " + playerId);
        }
        return player;
    }

    public Player getOpponent(String playerId) {
        getPlayer(playerId);
        for (Player player : players.values()) {
            if (!player.getId().equals(playerId)) {
                return player;
            }
        }
        throw new IllegalStateException("Aucun adversaire pour : " + playerId);
    }

    public Turn getTurn() {
        return turn;
    }

    public Player getActivePlayer() {
        return getPlayer(turn.getActivePlayerId());
    }

    public Phase getPhase() {
        return turn.getPhase();
    }

    public void setPhase(Phase phase) {
        turn.setPhase(phase);
    }

    public long getSeed() {
        return seed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isGameOver() {
        return winnerId != null;
    }

    public String getWinnerId() {
        return winnerId;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setWinner(String winnerId, String endReason) {
        if (isGameOver()) {
            return;
        }
        this.winnerId = winnerId;
        this.endReason = endReason;
    }

    /** Journal complet (lecture seule ; les événements sont immuables et sans secret). */
    public List<GameEvent> getEventLog() {
        return Collections.unmodifiableList(eventLog);
    }

    public void appendEvent(GameEvent event) {
        eventLog.add(event);
    }

    public void appendEvent(GameEventType type, String playerId, String description) {
        eventLog.add(new GameEvent(type, playerId, description));
    }

    // ------------------------------------------------------------------
    // Fenêtre de réaction (QUICK uniquement)
    // ------------------------------------------------------------------

    public boolean isReactionWindowOpen() {
        return reactionWindow != null;
    }

    public ReactionWindow getReactionWindow() {
        return reactionWindow;
    }

    public void openReactionWindow(String defendingPlayerId, String attackerInstanceId) {
        this.reactionWindow = new ReactionWindow(defendingPlayerId, attackerInstanceId);
    }

    public void closeReactionWindow() {
        this.reactionWindow = null;
    }

    // ------------------------------------------------------------------
    // Recherche et mesures
    // ------------------------------------------------------------------

    /** Cherche un exemplaire dans toutes les zones des deux joueurs. */
    public Optional<CardInstance> findInstance(UUID instanceId) {
        for (Player player : players.values()) {
            Optional<CardInstance> found = player.findAnywhere(instanceId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Propriétaire d'un exemplaire (vide si introuvable). */
    public Optional<Player> findInstanceOwner(UUID instanceId) {
        for (Player player : players.values()) {
            if (player.findAnywhere(instanceId).isPresent()) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    /**
     * Puissance totale d'une carte pour le combat : puissance effective propre
     * + puissances des Gears attachés présents sur le Field.
     */
    public int totalPowerFor(CardInstance card) {
        int total = card.getEffectivePowerOrZero();
        for (UUID attachmentId : card.getAttachments()) {
            Optional<CardInstance> attachment = findInstance(attachmentId);
            if (attachment.isPresent() && attachment.get().getZone() == Zone.FIELD) {
                total += attachment.get().getEffectivePowerOrZero();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Mécaniques (sans journalisation, voir la javadoc de classe)
    // ------------------------------------------------------------------

    /**
     * Pioche {@code count} cartes du dessus vers la main.
     * Un deck vide en cours de pioche fait perdre immédiatement son propriétaire.
     *
     * @return le nombre de cartes effectivement piochées
     */
    public int drawCards(String playerId, int count) {
        if (isGameOver() || count <= 0) {
            return 0;
        }
        Player player = getPlayer(playerId);
        int drawn = 0;
        for (int i = 0; i < count; i++) {
            if (player.getDeck().isEmpty()) {
                setWinner(getOpponent(playerId).getId(),
                        "deck-out : " + playerId + " devait piocher avec un deck vide");
                break;
            }
            CardInstance top = player.getDeck().remove(0);
            top.setZone(Zone.HAND);
            player.getHand().add(top);
            drawn++;
        }
        return drawn;
    }

    /**
     * Vole un Gig : le dé de plus forte valeur passe au voleur.
     *
     * @return la valeur volée, ou vide si la victime ne contrôle aucun Gig
     */
    public Optional<Integer> stealGig(String fromPlayerId, String toPlayerId) {
        Player from = getPlayer(fromPlayerId);
        Player to = getPlayer(toPlayerId);
        if (from.getGigs().isEmpty()) {
            return Optional.empty();
        }
        int bestIndex = 0;
        for (int i = 1; i < from.getGigs().size(); i++) {
            if (from.getGigs().get(i) > from.getGigs().get(bestIndex)) {
                bestIndex = i;
            }
        }
        Integer stolen = from.getGigs().remove(bestIndex);
        to.getGigs().add(stolen);
        return Optional.of(stolen);
    }

    /**
     * Lance le prochain dé de la Fixer Area du joueur et place le résultat
     * dans sa Gig Area.
     *
     * @return le lancer, ou vide s'il ne reste aucun dé
     */
    public Optional<DieRoll> rollFixerDie(String playerId) {
        Player player = getPlayer(playerId);
        Optional<String> die = player.popFixerDie();
        if (!die.isPresent()) {
            return Optional.empty();
        }
        int sides = Player.sidesOf(die.get());
        int value = random.nextInt(sides) + 1;
        player.getGigs().add(value);
        return Optional.of(new DieRoll(die.get(), value));
    }

    // ------------------------------------------------------------------
    // Vues
    // ------------------------------------------------------------------

    /**
     * Copie détachée où les secrets des autres joueurs sont masqués
     * (voir {@link Player#copy(boolean)}). Un observateur inconnu ne voit
     * les secrets de personne. La copie ne doit jamais servir à tirer
     * (son générateur n'est pas rejouable).
     */
    public GameState maskedCopyFor(String viewerPlayerId) {
        List<Player> copies = new ArrayList<Player>();
        for (Player player : players.values()) {
            copies.add(player.copy(!player.getId().equals(viewerPlayerId)));
        }
        List<GameEvent> events = new ArrayList<GameEvent>(eventLog);
        return new GameState(gameId, copies, seed, new Random(), turn.copy(),
                reactionWindow == null ? null : reactionWindow.copy(),
                winnerId, endReason, createdAt, events);
    }

    @Override
    public String toString() {
        return "GameState{gameId='" + gameId + "', turn=" + turn + ", winnerId='" + winnerId + "'}";
    }
}
