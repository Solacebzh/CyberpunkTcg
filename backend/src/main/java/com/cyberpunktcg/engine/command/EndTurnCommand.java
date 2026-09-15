package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DieRoll;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
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
 * <li><strong>victoire immédiate si le joueur entrant contrôle au moins
 *   {@link GameConstants#GIGS_TO_WIN} Gigs</strong> (avant pioche et Gig du tour, jamais en continu après un vol) ;</li>
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
    public String actionType() {
        return "END_TURN";
    }

    @Override
    public String describe() {
        return "terminer le tour";
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
        state.logInfo(playerId, "PHASE", "Phase END : Joueur " + playerId + " termine le tour "
                + state.getTurn().getNumber(), GameLog.details("phase", "END"));

        List<CardInstance> endingField = new ArrayList<CardInstance>(outgoing.getField());
        for (CardInstance card : endingField) {
            engine.resolveEffects(state, card, TriggerType.ON_TURN_END, null);
            if (state.isGameOver()) {
                return GameCommand.eventsSince(state, mark);
            }
        }

        if (state.isReactionWindowOpen()) {
            // Règles §6 : une fenêtre non utilisée ne survit pas à la fin du tour.
            String defender = state.getReactionWindow().getDefendingPlayerId();
            state.closeReactionWindow();
            state.appendEvent(GameEventType.REACTION_WINDOW_CLOSED, playerId,
                    "fenêtre de réaction fermée");
            state.logInfo(defender, "REACTION_WINDOW_CLOSED",
                    "Fenêtre de réaction fermée en fin de tour (aucune réaction jouée pendant la fenêtre)",
                    GameLog.details("defender", defender));
        }

        Player incoming = state.getOpponent(playerId);
        state.getTurn().setNumber(state.getTurn().getNumber() + 1);
        state.getTurn().setActivePlayerId(incoming.getId());
        state.appendEvent(GameEventType.TURN_STARTED, incoming.getId(),
                "début du tour " + state.getTurn().getNumber());
        state.setPhase(Phase.DRAW);
        state.logInfo(incoming.getId(), "PHASE",
                "Début du tour " + state.getTurn().getNumber() + " — Joueur " + incoming.getId()
                        + " (phase DRAW)",
                GameLog.details("turn", state.getTurn().getNumber(), "phase", "DRAW"));

        // Règle imposée : la victoire se vérifie AU DÉBUT du tour, avant pioche et lancer de Gig.
        state.logInfo(incoming.getId(), "VICTORY_CHECK",
                "Vérification victoire : Joueur " + incoming.getId() + " a "
                        + incoming.getGigCount() + "/" + GameConstants.GIGS_TO_WIN + " Gigs",
                GameLog.details("gigs", incoming.getGigCount(), "gigsToWin", GameConstants.GIGS_TO_WIN,
                        "passed", incoming.getGigCount() < GameConstants.GIGS_TO_WIN));
        if (incoming.getGigCount() >= GameConstants.GIGS_TO_WIN) {
            state.setWinner(incoming.getId(),
                    "victoire : " + incoming.getId() + " commence son tour avec "
                            + incoming.getGigCount() + " Gigs");
            state.appendEvent(GameEventType.GAME_WON, incoming.getId(), state.getEndReason());
            state.logSuccess(incoming.getId(), "VICTORY",
                    "VICTOIRE : Joueur " + incoming.getId() + " atteint " + incoming.getGigCount()
                            + " Gigs ! (vérifié au début de son tour)",
                    GameLog.details("gigs", incoming.getGigCount(),
                            "gigsToWin", GameConstants.GIGS_TO_WIN, "turn", state.getTurn().getNumber()));
            return GameCommand.eventsSince(state, mark);
        }

        incoming.startTurn();
        state.logInfo(incoming.getId(), "TURN_RESET",
                "Début de tour : Units du Field redressées, mals d'invocation dissipés ; "
                        + incoming.legendsAvailableForEddies().size()
                        + " Legend(s) encore disponibles pour incliner (+1 Eddie chacune)",
                GameLog.details("legendsReady", incoming.legendsAvailableForEddies().size(),
                        "eddies", incoming.getEddies()));

        int drawn = state.drawCards(incoming.getId(), 1);
        if (state.isGameOver()) {
            // Règle officielle §7 : devoir piocher avec un deck vide fait perdre la partie.
            state.logFailed(incoming.getId(), "DRAW",
                    "Phase DRAW : Joueur " + incoming.getId()
                            + " doit piocher mais son deck est vide → DÉFAITE",
                    GameLog.details("deck", 0, "phase", "DRAW"));
            state.logSuccess(state.getWinnerId(), "VICTORY",
                    "VICTOIRE : Joueur " + state.getWinnerId() + " gagne par deck-out de "
                            + incoming.getId(),
                    GameLog.details("reason", state.getEndReason()));
            return GameCommand.eventsSince(state, mark);
        }
        if (drawn > 0) {
            CardInstance drawnCard = incoming.getHand().get(incoming.getHand().size() - 1);
            state.appendEvent(GameEventType.CARD_DRAWN, incoming.getId(), "pioche 1 carte");
            state.logSuccess(incoming.getId(), "DRAW",
                    "Phase DRAW : Joueur " + incoming.getId() + " pioche " + drawnCard.getName(),
                    GameLog.details("card", drawnCard.getName(), "cardId", drawnCard.getCardId(),
                            "hand", incoming.getHand().size(), "deck", incoming.getDeck().size(),
                            "phase", "DRAW"));
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
            state.logSuccess(incoming.getId(), "GIG_ROLL",
                    "Lancer de Gig : " + roll.get().getDie() + " → " + roll.get().getValue()
                            + " (total " + incoming.getGigCount() + " Gigs, Street Cred "
                            + incoming.getStreetCred() + ")",
                    GameLog.details("die", roll.get().getDie(), "value", roll.get().getValue(),
                            "gigs", incoming.getGigCount(), "streetCred", incoming.getStreetCred()));
        }

        state.setPhase(Phase.MAIN);
        state.appendEvent(GameEventType.PHASE_CHANGED, incoming.getId(), "phase Main");
        state.logInfo(incoming.getId(), "PHASE", "Phase MAIN : Joueur " + incoming.getId()
                + " peut jouer, incliner ses Legends, vendre 1 carte et attaquer",
                GameLog.details("phase", "MAIN", "hand", incoming.getHand().size(),
                        "eddies", incoming.getEddies()));
        return GameCommand.eventsSince(state, mark);
    }
}
