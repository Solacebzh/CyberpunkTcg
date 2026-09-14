package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DieRoll;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.RuleEngine;
import com.cyberpunktcg.engine.TriggerType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Termine le tour du joueur actif et ouvre celui du rival :
 * <ol>
 *   <li>phase {@code END} + déclencheurs {@code ON_TURN_END} du joueur sortant ;</li>
 *   <li>fermeture de la fenêtre de réaction ;</li>
 *   <li>passage du tour ;</li>
 *   <li><strong>victoire immédiate si le joueur entrant contrôle au moins
 *   {@link GameConstants#GIGS_TO_WIN} Gigs</strong> (avant pioche et Gig du tour) ;</li>
 *   <li>sinon : réinitialisation du joueur entrant, pioche 1 (deck vide → défaite),
 *   lancer d'un dé Gig, phase {@code MAIN}.</li>
 * </ol>
 */
public class EndTurnCommand implements GameCommand {

    private final String playerId;

    public EndTurnCommand(String playerId) {
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
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        GameCommand.requireActivePlayer(state, playerId);
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player outgoing = state.getPlayer(playerId);
        RuleEngine engine = new RuleEngine();

        state.setPhase(Phase.END);
        state.appendEvent(GameEventType.PHASE_CHANGED, playerId, "phase End");
        state.appendEvent(GameEventType.TURN_ENDED, playerId,
                "fin du tour " + state.getTurn().getNumber());

        List<CardInstance> endingField = new ArrayList<CardInstance>(outgoing.getField());
        for (CardInstance card : endingField) {
            engine.resolveEffects(state, card, TriggerType.ON_TURN_END, null);
            if (state.isGameOver()) {
                return GameCommand.eventsSince(state, mark);
            }
        }

        if (state.isReactionWindowOpen()) {
            state.closeReactionWindow();
            state.appendEvent(GameEventType.REACTION_WINDOW_CLOSED, playerId,
                    "fenêtre de réaction fermée");
        }

        Player incoming = state.getOpponent(playerId);
        state.getTurn().setNumber(state.getTurn().getNumber() + 1);
        state.getTurn().setActivePlayerId(incoming.getId());
        state.appendEvent(GameEventType.TURN_STARTED, incoming.getId(),
                "début du tour " + state.getTurn().getNumber());

        if (incoming.getGigCount() >= GameConstants.GIGS_TO_WIN) {
            state.setWinner(incoming.getId(),
                    "victoire : " + incoming.getId() + " commence son tour avec "
                            + incoming.getGigCount() + " Gigs");
            state.appendEvent(GameEventType.GAME_WON, incoming.getId(), state.getEndReason());
            return GameCommand.eventsSince(state, mark);
        }

        state.setPhase(Phase.DRAW);
        incoming.startTurn();

        int drawn = state.drawCards(incoming.getId(), 1);
        if (drawn > 0) {
            state.appendEvent(GameEventType.CARD_DRAWN, incoming.getId(), "pioche 1 carte");
        }
        if (state.isGameOver()) {
            state.appendEvent(GameEventType.GAME_WON, state.getWinnerId(), state.getEndReason());
            return GameCommand.eventsSince(state, mark);
        }

        Optional<DieRoll> roll = state.rollFixerDie(incoming.getId());
        if (roll.isPresent()) {
            state.appendEvent(GameEventType.GIG_ROLLED, incoming.getId(),
                    "lancer " + roll.get().getDie() + " → " + roll.get().getValue()
                            + " (total " + incoming.getGigCount() + " Gigs)");
        }

        state.setPhase(Phase.MAIN);
        state.appendEvent(GameEventType.PHASE_CHANGED, incoming.getId(), "phase Main");
        return GameCommand.eventsSince(state, mark);
    }
}
