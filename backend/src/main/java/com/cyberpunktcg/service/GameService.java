package com.cyberpunktcg.service;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.GameCommand;
import com.cyberpunktcg.repository.CardRepository;
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
 */
@Service
public class GameService {

    private final CardRepository cardRepository;
    private final Map<String, GameState> games = new ConcurrentHashMap<String, GameState>();

    public GameService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Crée une partie 1v1 : les IDs de type {@code legend} rejoignent la Legends Area
     * (face cachée), les autres forment le deck (mélangé). Chaque joueur reçoit
     * {@link GameConstants#STARTING_HAND_SIZE} cartes. Le joueur 1 commence en phase Main.
     *
     * @param playerOneId    joueur 1 (commence)
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

        List<Player> players = new ArrayList<Player>();
        players.add(playerOne);
        players.add(playerTwo);
        GameState state = new GameState(UUID.randomUUID().toString(), players, seed);
        state.appendEvent(GameEventType.TURN_STARTED, playerOneId,
                "début de la partie (tour 1, " + playerOneId + " commence)");
        games.put(state.getGameId(), state);
        return state;
    }

    /**
     * Valide puis exécute une commande sur une partie.
     *
     * @return les événements produits
     * @throws ResponseStatusException 404 si la partie est inconnue
     * @throws GameRuleException si la commande est illégale
     */
    public List<GameEvent> executeCommand(String gameId, GameCommand command) {
        GameState state = requireGame(gameId);
        command.validate(state);
        return command.execute(state);
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
     * Ne jamais l'exposer tel quel aux clients.
     *
     * @throws ResponseStatusException 404 si la partie est inconnue
     */
    public GameState getGameStateInternal(String gameId) {
        return requireGame(gameId);
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
