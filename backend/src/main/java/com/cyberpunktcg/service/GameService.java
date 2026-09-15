package com.cyberpunktcg.service;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameLogEntry;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.GameCommand;
import com.cyberpunktcg.repository.CardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestre les parties 1v1 : création, exécution des commandes, vues masquées.
 *
 * <p>Les états vivent en mémoire (une instance suffit pour un jeu entre amis) ;
 * aucune règle de jeu ne vit ici — tout est délégué aux commandes du moteur,
 * qui valident, exécutent et détectent la victoire. Les vues exposées aux
 * joueurs sont systématiquement masquées (voir
 * {@link GameState#maskedCopyFor(String)}).</p>
 *
 * <p>Depuis la feature 6.5, le service tient aussi le <strong>journal de
 * diagnostic</strong> ({@link GameLog}) : chaque action acceptée y est consignée
 * par la commande, et chaque action refusée par le service (avec son motif), ce
 * qui rend les règles observables en jeu (panneau de debug, {@code /api/debug}).</p>
 */
@Service
public class GameService {

    private final CardRepository cardRepository;
    private final Map<String, GameState> games = new ConcurrentHashMap<String, GameState>();
    /** Graine dédiée aux choix de mise en place (premier joueur) — jamais exposée. */
    private final Random setupRandom;

    /** Constructeur Spring : graine de mise en place aléatoire. */
    @Autowired
    public GameService(CardRepository cardRepository) {
        this(cardRepository, new Random());
    }

    /** Constructeur de test : permet de fixer la graine de mise en place. */
    public GameService(CardRepository cardRepository, Random setupRandom) {
        this.cardRepository = cardRepository;
        this.setupRandom = setupRandom == null ? new Random() : setupRandom;
    }

    /**
     * Crée une partie 1v1 : les IDs de type {@code legend} rejoignent la Legends Area
     * (face cachée), les autres forment le deck (mélangé). Chaque joueur reçoit
     * {@link GameConstants#STARTING_HAND_SIZE} cartes. Le <strong>premier joueur est
     * tiré au sort</strong> ; il commence en phase {@code MAIN} et subit le malus de
     * mise en place (R1.4 : ses 2 Legends les plus à gauche sont déjà inclinées, il
     * ne peut donc encaisser qu'un Eddie en inclinant la troisième). Le second
     * joueur ne subit aucun malus (R1.5 : ses 3 Legends sont prêtes). Le malus est
     * levé dès le tour 2 du premier joueur : la phase {@code DRAW} redresse toutes
     * les cartes dépensées et remet les Eddies à 0.
     *
     * @param playerOneId    joueur 1 (candidat au premier tour)
     * @param playerTwoId    joueur 2
     * @param deckOneCardIds définitions du joueur 1 (legends + deck principal)
     * @param deckTwoCardIds définitions du joueur 2 (legends + deck principal)
     * @return l'état serveur (non masqué) de la nouvelle partie
     * @throws ResponseStatusException 404 si une définition de carte est inconnue
     */
    @Transactional(readOnly = true)
    public GameState createGame(String playerOneId, String playerTwoId,
                                List<String> deckOneCardIds, List<String> deckTwoCardIds) {
        if (playerOneId == null || playerTwoId == null || playerOneId.equals(playerTwoId)) {
            throw new IllegalArgumentException("Deux joueurs distincts sont requis");
        }
        if (deckOneCardIds == null || deckOneCardIds.isEmpty()
                || deckTwoCardIds == null || deckTwoCardIds.isEmpty()) {
            throw new IllegalArgumentException("Les deux decks doivent contenir au moins une carte");
        }

        long seed = new Random().nextLong();
        Player playerOne = buildPlayer(playerOneId, deckOneCardIds, new Random(seed));
        Player playerTwo = buildPlayer(playerTwoId, deckTwoCardIds, new Random(seed + 1));

        // Premier joueur tiré au sort (règle de mise en place, feature 6.5).
        boolean playerOneStarts = setupRandom.nextBoolean();
        Player starter = playerOneStarts ? playerOne : playerTwo;
        Player second = playerOneStarts ? playerTwo : playerOne;

        // R1.4 : le premier joueur incline ses 2 Legends les plus à gauche (malus de
        // mise en place, aucun Eddie gagné) ; R1.5 : le second joueur garde ses
        // 3 Legends prêtes. La phase DRAW du tour suivant redresse tout le monde.
        int firstPlayerPenalty = applyFirstPlayerPenalty(starter);

        List<Player> players = new ArrayList<Player>();
        players.add(starter);
        players.add(second);
        GameState state = new GameState(UUID.randomUUID().toString(), players, seed);

        state.appendEvent(GameEventType.TURN_STARTED, starter.getId(),
                "début de la partie (tour 1, " + starter.getId() + " commence)");
        state.logInfo(null, "GAME_START",
                "Nouvelle partie : Joueur " + starter.getId() + " commence (premier joueur tiré au sort)",
                GameLog.details("starter", starter.getId(), "second", second.getId(),
                        "seed", seed, "phase", state.getPhase().name()));
        logSetup(state, starter, true, firstPlayerPenalty);
        logSetup(state, second, false, 0);
        games.put(state.getGameId(), state);
        return state;
    }

    /**
     * Valide puis exécute une commande sur une partie.
     *
     * <p>Toute action — acceptée ou refusée — produit une entrée dans le journal
     * de diagnostic ({@link GameState#getGameLog()}), ce qui permet de comprendre
     * en jeu pourquoi une action ne fonctionne pas.</p>
     *
     * @return les événements produits
     * @throws ResponseStatusException 404 si la partie est inconnue
     * @throws GameRuleException si la commande est illégale
     */
    public List<GameEvent> executeCommand(String gameId, GameCommand command) {
        GameState state = requireGame(gameId);
        if (command == null) {
            throw new IllegalArgumentException("Commande obligatoire");
        }
        String playerId = command.getPlayerId();
        try {
            command.validate(state);
        } catch (GameRuleException error) {
            logRefusal(state, playerId, command, error.getMessage());
            throw error;
        }
        try {
            List<GameEvent> events = command.execute(state);
            if (events.isEmpty()) {
                state.log(playerId, command.actionType(), command.describe(state) + " → aucun effet",
                        com.cyberpunktcg.domain.game.GameActionResult.FAILED, state.snapshotDetails());
            }
            return events;
        } catch (GameRuleException error) {
            logRefusal(state, playerId, command, error.getMessage());
            throw error;
        }
    }

    /**
     * Consigne une action refusée hors du moteur (transport STOMP : action
     * inconnue, cible illisible…). Aucune mutation d'état.
     *
     * @return l'entrée consignée, ou {@code null} si la partie est inconnue
     */
    public GameLogEntry logExternalRefusal(String gameId, String playerId, String actionType,
                                           String description, Map<String, Object> details) {
        GameState state = games.get(gameId);
        if (state == null) {
            return null;
        }
        return state.logIllegal(playerId, actionType, description, details);
    }

    /**
     * Abandon d'un joueur (action CONCEDE ou forfait après expiration du
     * délai de reconnexion). L'adversaire est déclaré vainqueur.
     *
     * @param reason libellé de la raison (« Abandon », « Forfait déconnexion »…)
     * @return l'événement GAME_WON généré, ou {@code null} si la partie était déjà finie
     */
    public GameEvent concede(String gameId, String playerId, String reason) {
        GameState state = requireGame(gameId);
        if (state.isGameOver()) {
            return null;
        }
        GameCommand.requireKnownPlayer(state, playerId);
        Player winner = state.getOpponent(playerId);
        String endReason = reason + " de " + playerId;
        state.setWinner(winner.getId(), endReason);
        state.appendEvent(GameEventType.GAME_WON, winner.getId(), endReason);
        state.logSuccess(winner.getId(), "CONCEDE", "FIN DE PARTIE : " + endReason
                        + " → victoire de Joueur " + winner.getId(),
                GameLog.details("reason", endReason, "winner", winner.getId(), "loser", playerId));
        return new GameEvent(GameEventType.GAME_WON, winner.getId(), endReason);
    }

    /**
     * Vue masquée d'une partie pour un joueur (secrets adverses effacés).
     * Un observateur inconnu ne voit les secrets de personne.
     *
     * @throws ResponseStatusException 404 si la partie est inconnue
     */
    public GameState getGameState(String gameId, String viewerPlayerId) {
        return requireGame(gameId).maskedCopyFor(viewerPlayerId);
    }

    /**
     * État serveur non masqué, réservé au serveur et aux tests.
     * Ne jamais l'exposer tel quel aux clients (sauf {@code /api/debug}, profil test/dev).
     *
     * @throws ResponseStatusException 404 si la partie est inconnue
     */
    public GameState getGameStateInternal(String gameId) {
        return requireGame(gameId);
    }

    /** Dernières entrées du journal de diagnostic ({@code limit} au plus). */
    public List<GameLogEntry> getGameLog(String gameId, int limit) {
        return requireGame(gameId).getGameLog().recent(limit);
    }

    /** Identifiants des parties en mémoire (ordre d'insertion, pour le debug). */
    public List<String> listGameIds() {
        return new ArrayList<String>(games.keySet());
    }

    /**
     * Force la phase d'une partie — <strong>réservé au debug</strong>
     * ({@code POST /api/debug/game/{gameId}/force-phase}) : permet de tester une
     * règle sans rejouer les phases précédentes. La manipulation est journalisée.
     *
     * @param gameId  partie visée
     * @param phase   phase cible
     * @param actorId joueur au nom duquel l'opération est journalisée (peut être {@code null})
     * @return l'état (serveur) après forçage
     */
    public GameState forcePhase(String gameId, Phase phase, String actorId) {
        GameState state = requireGame(gameId);
        if (phase == null) {
            throw new IllegalArgumentException("Phase cible obligatoire (DRAW, MAIN, COMBAT, END)");
        }
        Phase previous = state.getPhase();
        state.setPhase(phase);
        if (phase == Phase.DRAW && state.getDrawStep() == null) {
            // Mini-Feature 5 : une phase DRAW forcée doit rester jouable — on attend la pioche.
            state.setDrawStep(DrawStep.AWAITING_DRAW);
        }
        state.appendEvent(GameEventType.PHASE_CHANGED, actorId,
                "phase forcée (debug) : " + previous + " → " + phase);
        state.logInfo(actorId, "DEBUG_FORCE_PHASE",
                "DEBUG : phase forcée " + previous + " → " + phase,
                GameLog.details("from", previous.name(), "to", phase.name(), "debug", true));
        return state;
    }

    // ------------------------------------------------------------------
    // Journalisation interne
    // ------------------------------------------------------------------

    private void logRefusal(GameState state, String playerId, GameCommand command, String reason) {
        state.logIllegal(playerId, command.actionType(),
                "Joueur " + playerId + " : " + command.describe(state) + " → REFUSÉ (" + reason + ")",
                GameLog.details("reason", reason, "intent", command.describe(state),
                        "command", command.getClass().getSimpleName()));
    }

    /**
     * Consigne la mise en place d'un joueur (R1) : main, deck, Legends — dont le
     * nombre effectivement incliné par le malus du premier joueur (R1.4/R1.5).
     *
     * @param appliedPenalty nombre de Legends inclinées par le malus ({@code 0} pour le second joueur)
     */
    private void logSetup(GameState state, Player player, boolean starter, int appliedPenalty) {
        Map<String, Object> details = new HashMap<String, Object>();
        details.put("hand", player.getHand().size());
        details.put("deck", player.getDeck().size());
        details.put("legends", player.getLegendsArea().size());
        details.put("legendsSpent", player.countSpentLegends());
        if (starter) {
            details.put("firstPlayerPenalty", appliedPenalty);
        }
        state.logInfo(player.getId(), "SETUP",
                "Mise en place de Joueur " + player.getId() + " : " + player.getHand().size()
                        + " cartes en main, " + player.getLegendsArea().size() + " Legends"
                        + (starter
                                ? " (premier joueur : " + appliedPenalty + " Legends déjà inclinées)"
                                : " (second joueur : 0 Legend inclinée)")
                        + ", " + player.getFixerDice().size() + " dés Gig",
                details);
    }

    /**
     * Applique le malus de mise en place du premier joueur (R1.4).
     *
     * <p>Règle officielle (§ SETUP · DETERMINE PLAY ORDER) : « The player going
     * first spends their 2 leftmost Legends and doesn't ready them on their first
     * turn. » Concrètement : 2 des 3 Legends de la Legends Area — les deux les
     * plus à gauche — commencent la partie <strong>déjà inclinées</strong>
     * ({@code exhausted = true}), sans avoir rapporté le moindre Eddie. Le
     * premier joueur ne peut donc encaisser qu'un seul Eddie pendant son tour 1,
     * en inclinant sa troisième Legend.</p>
     *
     * <p>Le second joueur ne subit aucun malus (R1.5) : ses 3 Legends sont prêtes.
     * Le malus est levé au début du tour suivant du premier joueur, la phase DRAW
     * redressant toutes les cartes dépensées (voir
     * {@link com.cyberpunktcg.engine.command.EndTurnCommand}).</p>
     *
     * @param starter joueur qui commence la partie
     * @return le nombre de Legends effectivement inclinées à la mise en place
     */
    private int applyFirstPlayerPenalty(Player starter) {
        return starter.exhaustLeftmostLegends(GameConstants.FIRST_PLAYER_SPENT_LEGENDS);
    }

    private Player buildPlayer(String playerId, List<String> cardIds, Random shuffle) {
        Map<String, Card> catalog = loadCatalog(cardIds);
        Player player = new Player(playerId, playerId);
        List<CardInstance> deck = new ArrayList<CardInstance>();
        for (String cardId : cardIds) {
            Card card = catalog.get(cardId);
            if (card.getType() == CardType.LEGEND) {
                CardInstance legend = CardInstance.fromCard(card, playerId, Zone.LEGENDS_AREA);
                legend.setFaceDown(true);
                player.getLegendsArea().add(legend);
            } else {
                deck.add(CardInstance.fromCard(card, playerId, Zone.DECK));
            }
        }
        Collections.shuffle(deck, shuffle);
        player.getDeck().addAll(deck);
        int deal = Math.min(GameConstants.STARTING_HAND_SIZE, player.getDeck().size());
        for (int i = 0; i < deal; i++) {
            CardInstance top = player.getDeck().remove(0);
            top.setZone(Zone.HAND);
            player.getHand().add(top);
        }
        return player;
    }

    private Map<String, Card> loadCatalog(List<String> cardIds) {
        List<Card> cards = cardRepository.findAllById(cardIds);
        Map<String, Card> catalog = new HashMap<String, Card>();
        for (Card card : cards) {
            catalog.put(card.getId(), card);
        }
        for (String cardId : cardIds) {
            if (!catalog.containsKey(cardId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Carte inconnue dans le deck : " + cardId);
            }
        }
        return catalog;
    }

    private GameState requireGame(String gameId) {
        GameState state = games.get(gameId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Partie introuvable : " + gameId);
        }
        return state;
    }
}
