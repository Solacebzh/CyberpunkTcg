package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.List;
import java.util.UUID;

/**
 * Vend une carte de la main : révélée puis placée face cachée dans l'Eddies Area,
 * elle rapporte exactement 1 Eddie quel que soit son coût imprimé.
 *
 * <p>Règle imposée : {@link GameConstants#SALES_PER_TURN} vente par tour maximum,
 * en phase {@code MAIN}. Aucune deuxième vente n'est autorisée durant ce tour.</p>
 */
public class SellCardCommand implements GameCommand {

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
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        Player player = GameCommand.requireActivePlayer(state, playerId);
        if (state.getPhase() != Phase.MAIN) {
            throw new GameRuleException("On ne vend qu'en phase Main");
        }
        if (player.hasSoldThisTurn()) {
            throw new GameRuleException("Une seule vente par tour (déjà effectuée)");
        }
        player.findIn(Zone.HAND, cardInstanceId)
                .orElseThrow(() -> new GameRuleException("On ne vend qu'une carte de sa main"));
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player player = state.getPlayer(playerId);
        CardInstance card = player.findIn(Zone.HAND, cardInstanceId).get();
        player.moveToZone(card, Zone.EDDIES_AREA);
        card.setFaceDown(true);
        player.addEddy();
        player.setHasSoldThisTurn(true);
        state.appendEvent(GameEventType.CARD_SOLD, playerId,
                "vente de " + card.getName() + " (+1 Eddie, total " + player.getEddies() + ")");
        return GameCommand.eventsSince(state, mark);
    }
}
