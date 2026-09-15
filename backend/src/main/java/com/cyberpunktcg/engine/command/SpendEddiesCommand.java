package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Incline une carte de l'Eddies Area pour gagner 1 Eddie — R6.
 * <p>
 * Même mécanique que les Legends : chaque carte face-down dans l'Eddies Area vaut 1 €$ par tour
 * (Guide § EDDIES “Each face-down card in your Eddies area is 1 Eddie. Spend them (turn them sideways) to pay”).
 * La carte est redressée au début du tour suivant (START PHASE — Ready).
 * </p>
 */
public class SpendEddiesCommand implements GameCommand {

    private final String playerId;
    private final UUID eddiesInstanceId;

    public SpendEddiesCommand(String playerId, UUID eddiesInstanceId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        if (eddiesInstanceId == null) {
            throw new IllegalArgumentException("La carte Eddies à incliner est obligatoire");
        }
        this.playerId = playerId;
        this.eddiesInstanceId = eddiesInstanceId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    public UUID getEddiesInstanceId() {
        return eddiesInstanceId;
    }

    @Override
    public String actionType() {
        return "SPEND_EDDIES";
    }

    @Override
    public String describe() {
        return "incliner une carte Eddies pour 1 Eddie";
    }

    @Override
    public void validate(GameState state) throws com.cyberpunktcg.engine.GameRuleException {
        GameCommand.requireGameOngoing(state);
        Player player = GameCommand.requireActivePlayer(state, playerId);
        Phase phase = state.getPhase();
        if (phase != Phase.MAIN && phase != Phase.COMBAT) {
            throw new com.cyberpunktcg.engine.GameRuleException("On n'incline une carte Eddies qu'en phase Main ou Combat");
        }
        Optional<CardInstance> card = player.findIn(Zone.EDDIES_AREA, eddiesInstanceId);
        if (!card.isPresent()) {
            throw new com.cyberpunktcg.engine.GameRuleException("Carte Eddies introuvable dans l'Eddies Area");
        }
        if (card.get().isExhausted()) {
            throw new com.cyberpunktcg.engine.GameRuleException("Cette carte Eddies est déjà inclinée");
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws com.cyberpunktcg.engine.GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player player = state.getPlayer(playerId);
        CardInstance card = player.spendEddiesCardForEddies(eddiesInstanceId);

        state.appendEvent(GameEventType.EFFECT_RESOLVED, playerId,
                "carte Eddies inclinée : " + card.getName() + " (+1 Eddie, total " + player.getEddies() + ")");
        state.logSuccess(playerId, actionType(),
                "Joueur " + playerId + " incline carte Eddies " + card.getName() + " → +1 Eddie (total "
                        + player.getEddies() + " Eddies, Eddies prêtes " + player.eddiesAvailableForEddies().size()
                        + "/" + player.getEddiesArea().size() + ")",
                GameLog.details(
                        "card", card.getName(),
                        "cardId", card.getCardId(),
                        "eddiesTotal", player.getEddies(),
                        "eddiesReady", player.eddiesAvailableForEddies().size()));
        return GameCommand.eventsSince(state, mark);
    }
}
