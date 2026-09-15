package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.engine.DrawPhaseHandler;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.RuleEngine;
import com.cyberpunktcg.engine.TriggerType;

import java.util.ArrayList;
import java.util.List;

/**
 * Termine le tour du joueur actif et ouvre celui du rival :
 * <ol>
 *   <li>phase {@code END} + déclencheurs {@code ON_TURN_END} du joueur sortant ;</li>
 *   <li>fermeture de la fenêtre de réaction ;</li>
 *   <li>passage du tour ;</li>
 * <li><strong>victoire immédiate si le joueur entrant contrôle au moins
 *   {@link GameConstants#GIGS_TO_WIN} Gigs</strong> (avant pioche et Gig du tour, jamais en continu après un vol) ;</li>
 *   <li>sinon : ouverture de la phase {@code DRAW} <strong>interactive</strong>
 *   (Mini-Feature 5, {@link DrawPhaseHandler}) : réinitialisation du joueur
 *   entrant puis attente de sa pioche ({@link DrawStep#AWAITING_DRAW}). La pioche
 *   ({@code DRAW_CARD}) et le choix du dé Gig ({@code SELECT_DIE}) sont des
 *   commandes distinctes : la phase {@code MAIN} ne s'ouvre qu'après elles.</li>
 * </ol>
 *
 * <p>Refusée pendant la phase {@code DRAW} : le joueur doit d'abord piocher et
 * choisir son dé.</p>
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
        if (state.getPhase() == Phase.DRAW) {
            throw new GameRuleException("Impossible de terminer le tour pendant la phase Draw : "
                    + (state.getDrawStep() == DrawStep.AWAITING_DIE_SELECT
                    ? "choisissez d'abord votre dé Gig" : "piochez d'abord votre carte"));
        }
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

        // R2 : Fin de tour — Eddies restants perdus (cycle mana)
        if (outgoing.getEddies() > 0) {
            int lost = outgoing.getEddies();
            outgoing.setEddies(0);
            state.logInfo(outgoing.getId(), "EDDIES_LOST",
                    "Fin de tour : Joueur " + outgoing.getId() + " perd " + lost + " Eddies restants (remise à 0)",
                    GameLog.details("lost", lost, "phase", "END"));
        }

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
        // Mini-Feature 5 : phase DRAW interactive (DRAW_START).
        DrawPhaseHandler.beginDrawPhase(state, incoming.getId());

        // Règle imposée : la victoire se vérifie AU DÉBUT du tour, avant pioche et lancer de Gig.
        state.logInfo(incoming.getId(), "VICTORY_CHECK",
                "Vérification victoire : Joueur " + incoming.getId() + " a "
                        + incoming.getGigCount() + "/" + GameConstants.GIGS_TO_WIN + " Gigs",
                GameLog.details("gigs", incoming.getGigCount(), "gigsToWin", GameConstants.GIGS_TO_WIN,
                        "passed", incoming.getGigCount() < GameConstants.GIGS_TO_WIN));
        if (incoming.getGigCount() >= GameConstants.GIGS_TO_WIN) {
            state.setDrawStep(null); // plus rien à piocher : la partie est finie
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

        // Mini-Feature 5 : on redresse tout et on s'arrête en AWAITING_DRAW ; la
        // suite (DRAW_CARD puis SELECT_DIE) est ordonnée par le joueur entrant.
        DrawPhaseHandler.readyAndAwaitDraw(state, incoming.getId());
        return GameCommand.eventsSince(state, mark);
    }
}
