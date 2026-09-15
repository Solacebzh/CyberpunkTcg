package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.engine.DrawPhaseHandler;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.List;

/**
 * Mini-Feature 5 — le joueur actif clique sur sa pioche pendant la phase
 * {@code DRAW} (étape {@link DrawStep#AWAITING_DRAW}) : il pioche 1 carte
 * (deck vide → défaite immédiate) puis la partie attend le choix du dé Gig
 * ({@link DrawStep#AWAITING_DIE_SELECT}).
 *
 * <p>Action filaire : {@code DRAW_CARD} (aucune cible).</p>
 */
public class DrawCardCommand implements GameCommand {

    private final String playerId;

    public DrawCardCommand(String playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        this.playerId = playerId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    @Override
    public String actionType() {
        return "DRAW_CARD";
    }

    @Override
    public String describe() {
        return "piocher la carte du tour";
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        GameCommand.requireActivePlayer(state, playerId);
        DrawPhaseHandler.requireStep(state, DrawStep.AWAITING_DRAW);
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        DrawPhaseHandler.drawCard(state, playerId);
        return GameCommand.eventsSince(state, mark);
    }
}
