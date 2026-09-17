package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.List;
import java.util.UUID;

/**
 * Vend une carte de la main — Mini-Feature 3 : <strong>la vente crée une ressource</strong>,
 * elle ne rapporte aucun Eddie immédiatement.
 *
 * <p>Déroulé officiel imposé (Guide § MAIN PHASE — « SELL FOR EDDIE (ONCE PER TURN) »
 * et § GLOSSARY — SELL : « reveal it to your Rival, then place it face-down in the
 * Eddies area ») :</p>
 * <ol>
 *   <li>la carte est <strong>révélée au rival</strong> — tracée par le journal
 *   ({@code CARD_REVEALED}, ligne {@code INFO}, et événement {@code EFFECT_RESOLVED}) ;</li>
 *   <li>elle est <strong>retirée de la main</strong> et placée dans
 *   {@link Zone#EDDIES_AREA} (ni {@code TRASH}, ni {@code FIELD}) ;</li>
 *   <li>elle y est posée {@code faceDown = true} (identité secrète pour le rival) et
 *   {@code exhausted = false} : elle est <strong>prête à être utilisée</strong>.</li>
 * </ol>
 *
 * <p>Aucun Eddie n'est crédité par cette commande : quel que soit son coût imprimé,
 * une carte vendue ne vaut que {@code 1} €$ <em>par tour</em>, obtenu en l'inclinant
 * ({@link SpendEddiesCommand}, R6). Comme elle est posée prête, elle peut être
 * inclinée dès le tour de la vente.</p>
 *
 * <p>Limites : {@link GameConstants#SALES_PER_TURN} vente par tour et par joueur
 * (marqueur {@link Player#hasSoldThisTurn()}, réinitialisé par {@code Player.startTurn()}),
 * en phase {@code MAIN} uniquement, par le joueur actif, sur une partie en cours.</p>
 *
 * <p><strong>Mini-Feature 10C — restriction de vente par type de carte</strong> :
 * seules les cartes qui ne sont ni des {@link CardType#UNIT Units} ni des
 * {@link CardType#LEGEND Legends} peuvent être vendues (les {@code PROGRAM},
 * {@code GEAR} et tout autre type restent vendables, toujours dans la limite d'1
 * vente par tour). Une tentative sur une Unit ou une Legend est refusée
 * ({@link GameRuleException} → code {@code ILLEGAL_ACTION} sur le canal WebSocket)
 * avec le motif « Les Unités et les Légendes ne peuvent pas être vendues ».</p>
 *
 * @see SpendEddiesCommand incliner une carte de l'Eddies Area pour gagner 1 €$
 */
public class SellCardCommand implements GameCommand {

    /**
     * Motif de refus de la Mini-Feature 10C : les Units et les Legends ne peuvent
     * pas être vendues (relayé tel quel par le code d'erreur {@code ILLEGAL_ACTION}
     * du canal WebSocket).
     */
    public static final String SELL_FORBIDDEN_TYPES_MESSAGE =
            "Les Unités et les Légendes ne peuvent pas être vendues";

    private final String playerId;
    private final UUID cardInstanceId;

    public SellCardCommand(String playerId, UUID cardInstanceId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        if (cardInstanceId == null) {
            throw new IllegalArgumentException("La carte à vendre est obligatoire");
        }
        this.playerId = playerId;
        this.cardInstanceId = cardInstanceId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    public UUID getCardInstanceId() {
        return cardInstanceId;
    }

    @Override
    public String actionType() {
        return "SELL_CARD";
    }

    @Override
    public String describe() {
        return playerId + " veut vendre une carte";
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        Player player = GameCommand.requireActivePlayer(state, playerId);
        if (state.getPhase() != Phase.MAIN) {
            throw new GameRuleException("On ne vend qu'en phase Main");
        }
        // R3.1 : GameConstants.SALES_PER_TURN (= 1) vente par tour, marqueur remis à
        // zéro au début du tour du joueur (Player.startTurn).
        if (player.hasSoldThisTurn()) {
            throw new GameRuleException("Une seule vente par tour (déjà effectuée)");
        }
        CardInstance card = player.findIn(Zone.HAND, cardInstanceId)
                .orElseThrow(() -> new GameRuleException("On ne vend qu'une carte de sa main"));
        // Mini-Feature 10C : restriction de vente par TYPE de carte — les Units et
        // les Legends ne portent pas de « sell tag » et ne sont jamais vendables ;
        // tous les autres types (PROGRAM, GEAR…) le restent, dans la limite d'1 vente/tour.
        if (!isSellableType(card.getType())) {
            throw new GameRuleException(SELL_FORBIDDEN_TYPES_MESSAGE);
        }
    }

    /**
     * Types de cartes vendables (Mini-Feature 10C) : tout sauf {@link CardType#UNIT}
     * et {@link CardType#LEGEND}.
     *
     * @param type type imprimé de la carte candidate à la vente
     * @return {@code true} si la carte peut être vendue depuis la main
     */
    public static boolean isSellableType(CardType type) {
        return type != CardType.UNIT && type != CardType.LEGEND;
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player player = state.getPlayer(playerId);
        CardInstance card = player.findIn(Zone.HAND, cardInstanceId).get();

        // 1. Révélation au rival : seule étape publique de la vente.
        state.appendEvent(GameEventType.EFFECT_RESOLVED, playerId,
                "carte révélée au rival : " + card.getName());
        state.logInfo(playerId, "CARD_REVEALED",
                "Joueur " + playerId + " montre " + card.getName() + " au rival (vente)",
                GameLog.details("card", card.getName(), "cardId", card.getCardId(),
                        "cost", card.getEffectiveCost(), "color", card.getColor() == null
                                ? null : card.getColor().value()));

        // 2. La carte quitte la main et rejoint l'Eddies Area.
        player.moveToZone(card, Zone.EDDIES_AREA);

        // 3. Elle y devient une ressource : face cachée et PRÊTE (exhausted = false),
        //    donc inclinable pour 1 €$ — y compris dès ce tour. Aucun Eddie crédité ici.
        card.setFaceDown(true);
        card.setExhausted(false);
        player.setHasSoldThisTurn(true);

        state.appendEvent(GameEventType.CARD_SOLD, playerId,
                "vente de " + card.getName() + " → Eddies Area (ressource face cachée, prête)");
        state.logSuccess(playerId, actionType(),
                "Joueur " + playerId + " vend " + card.getName() + " → Eddies Area face cachée,"
                        + " prête à incliner (0 Eddie immédiat, total " + player.getEddies()
                        + " Eddies, " + GameConstants.SALES_PER_TURN + " vente/tour)",
                GameLog.details("card", card.getName(), "cardId", card.getCardId(),
                        "zone", Zone.EDDIES_AREA.name(), "faceDown", card.isFaceDown(),
                        "exhausted", card.isExhausted(),
                        // eddieGained = 0 : la vente crée la ressource, l'Eddie est gagné
                        // en l'inclinant (SPEND_EDDIES, R6).
                        "eddieGained", 0, "eddiesTotal", player.getEddies(),
                        "eddiesReady", player.eddiesAvailableForEddies().size(),
                        "salesPerTurn", GameConstants.SALES_PER_TURN));
        return GameCommand.eventsSince(state, mark);
    }
}
