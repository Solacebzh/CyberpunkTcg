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
    /** Attaque en cours de résolution (Mini-Feature 6) : {@code null} = combat résolu. */
    private PendingAttack pendingAttack;
    private String winnerId;
    private String endReason;
    private final long seed;
    private final Random random;
    private Instant createdAt;
    private final List<GameEvent> eventLog;
    /** Journal de diagnostic (feature 6.5) : chaque action, y compris refusée. */
    private final GameLog gameLog;

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
        this.pendingAttack = null;
        this.winnerId = null;
        this.endReason = null;
        this.seed = seed;
        this.random = new Random(seed);
        this.createdAt = Instant.now();
        this.eventLog = new ArrayList<GameEvent>();
        this.gameLog = new GameLog();
    }

    /** Constructeur interne des copies (vues masquées : tirages jamais utilisés). */
    private GameState(String gameId, List<Player> players, long seed, Random random,
                      Turn turn, ReactionWindow reactionWindow, PendingAttack pendingAttack,
                      String winnerId, String endReason, Instant createdAt,
                      List<GameEvent> eventLog, GameLog gameLog) {
        this.gameId = gameId;
        this.players = new LinkedHashMap<String, Player>();
        for (Player player : players) {
            this.players.put(player.getId(), player);
        }
        this.turn = turn;
        this.reactionWindow = reactionWindow;
        this.pendingAttack = pendingAttack;
        this.winnerId = winnerId;
        this.endReason = endReason;
        this.seed = seed;
        this.random = random;
        this.createdAt = createdAt;
        this.eventLog = eventLog;
        this.gameLog = gameLog;
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

    /** Sous-étape de la phase DRAW (Mini-Feature 5), {@code null} hors DRAW. */
    public DrawStep getDrawStep() {
        return turn.getDrawStep();
    }

    public void setDrawStep(DrawStep drawStep) {
        turn.setDrawStep(drawStep);
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
    // Journal de diagnostic (feature 6.5)
    // ------------------------------------------------------------------

    /** Journal de diagnostic de la partie (borné, lecture seule). */
    public GameLog getGameLog() {
        return gameLog;
    }

    /**
     * Consigne une action dans le journal de diagnostic.
     *
     * @param playerId    joueur à l'origine de l'action ({@code null} = système)
     * @param actionType  type technique ({@code PLAY_CARD}, {@code ATTACK}, {@code VICTORY}…)
     * @param description libellé lisible (« Joueur V joue … »)
     * @param result      verdict ({@code SUCCESS}, {@code FAILED}, {@code ILLEGAL}, {@code INFO})
     * @param details     contexte JSON-compatible (coûts, cibles, ressources), peut être {@code null}
     * @return l'entrée consignée
     */
    public GameLogEntry log(String playerId, String actionType, String description,
                            GameActionResult result, Map<String, Object> details) {
        return gameLog.append(turn.getNumber(), turn.getPhase(), playerId, actionType, description,
                result, details);
    }

    /** Ligne narrative (phase, vérification de victoire…). */
    public GameLogEntry logInfo(String playerId, String actionType, String description,
                                Map<String, Object> details) {
        return log(playerId, actionType, description, GameActionResult.INFO, details);
    }

    /** Action acceptée. */
    public GameLogEntry logSuccess(String playerId, String actionType, String description,
                                   Map<String, Object> details) {
        return log(playerId, actionType, description, GameActionResult.SUCCESS, details);
    }

    /** Action légale sans effet (cible invalide, plus rien à voler…). */
    public GameLogEntry logFailed(String playerId, String actionType, String description,
                                  Map<String, Object> details) {
        return log(playerId, actionType, description, GameActionResult.FAILED, details);
    }

    /** Action refusée par le moteur : aucune mutation n'a eu lieu. */
    public GameLogEntry logIllegal(String playerId, String actionType, String description,
                                   Map<String, Object> details) {
        return log(playerId, actionType, description, GameActionResult.ILLEGAL, details);
    }

    /**
     * Photographie courte de l'état, utilisée comme {@code details} par défaut des
     * entrées de journal (phase, tour, ressources des deux joueurs).
     */
    public Map<String, Object> snapshotDetails() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("turn", turn.getNumber());
        details.put("phase", turn.getPhase().name());
        details.put("activePlayerId", turn.getActivePlayerId());
        for (Player player : players.values()) {
            details.put(player.getId() + ".gigs", player.getGigCount());
            details.put(player.getId() + ".eddies", player.getEddies());
            details.put(player.getId() + ".hand", player.getHand().size());
            details.put(player.getId() + ".deck", player.getDeck().size());
        }
        return details;
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
    // Attaque en cours (Mini-Feature 6 : blocage et choix des dés volés)
    // ------------------------------------------------------------------

    /** Attaque en cours de résolution, {@code null} si le combat est résolu. */
    public PendingAttack getPendingAttack() {
        return pendingAttack;
    }

    public void setPendingAttack(PendingAttack pendingAttack) {
        this.pendingAttack = pendingAttack;
    }

    /** Oublie l'attaque en cours (combat résolu, attaque annulée, fin de tour). */
    public void clearPendingAttack() {
        this.pendingAttack = null;
    }

    /** {@code true} tant qu'une attaque n'est pas résolue. */
    public boolean isCombatPending() {
        return pendingAttack != null;
    }

    /** {@code true} si le défenseur doit répondre à la fenêtre « Utiliser Blocker ? ». */
    public boolean isAwaitingBlock() {
        return pendingAttack != null && pendingAttack.getStep() == CombatStep.AWAITING_BLOCK;
    }

    /** {@code true} si l'attaquant doit choisir les dés Gigs à voler. */
    public boolean isAwaitingStealChoice() {
        return pendingAttack != null && pendingAttack.getStep() == CombatStep.AWAITING_STEAL_CHOICE;
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
        // Le type de dé suit le Gig volé (Mini-Feature 5 : affichage « d8 → 5 »).
        DieRoll stolen = from.removeGig(bestIndex);
        to.addRolledGig(stolen.getDie(), stolen.getValue());
        return Optional.of(stolen.getValue());
    }

    /**
     * Vole un dé Gig <strong>précis</strong> (Mini-Feature 6) : l'attaquant
     * choisit les dés qu'il vole parmi les dés actifs du défenseur. Le dé
     * transféré conserve son identifiant, son type et sa valeur exacte.
     *
     * @param dieId identifiant d'un dé actif de la Gig Area de {@code fromPlayerId}
     * @return le dé transféré, ou vide si l'identifiant ne correspond à aucun dé actif
     */
    public Optional<GigDie> stealGig(String fromPlayerId, String toPlayerId, String dieId) {
        Player from = getPlayer(fromPlayerId);
        Player to = getPlayer(toPlayerId);
        Optional<GigDie> stolen = from.removeGigById(dieId);
        if (!stolen.isPresent()) {
            return Optional.empty();
        }
        to.addGigDie(stolen.get());
        return stolen;
    }

    /**
     * Lance le prochain dé de la Fixer Area du joueur (le plus petit disponible,
     * {@code d20} en dernier) et place le résultat dans sa Gig Area.
     *
     * @return le lancer, ou vide s'il ne reste aucun dé
     */
    public Optional<DieRoll> rollFixerDie(String playerId) {
        Player player = getPlayer(playerId);
        List<String> selectable = player.selectableFixerDice();
        if (selectable.isEmpty()) {
            return Optional.empty();
        }
        return rollFixerDie(playerId, selectable.get(0));
    }

    /**
     * Lance un dé précis de la Fixer Area du joueur (Mini-Feature 5 : dé choisi
     * par le joueur) et place le résultat dans sa Gig Area. Le tirage utilise le
     * générateur de la partie (rejeu déterministe).
     *
     * @param die dé déjà normalisé ({@code "d4"}…{@code "d20"})
     * @return le lancer, ou vide si le dé n'est pas dans la Fixer Area du joueur
     */
    public Optional<DieRoll> rollFixerDie(String playerId, String die) {
        Player player = getPlayer(playerId);
        if (!player.removeFixerDie(die)) {
            return Optional.empty();
        }
        int sides = Player.sidesOf(die);
        int value = random.nextInt(sides) + 1;
        player.addRolledGig(die, value);
        return Optional.of(new DieRoll(die, value));
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
                pendingAttack == null ? null : pendingAttack.copy(),
                winnerId, endReason, createdAt, events, gameLog.copy());
    }

    @Override
    public String toString() {
        return "GameState{gameId='" + gameId + "', turn=" + turn + ", winnerId='" + winnerId + "'}";
    }
}
