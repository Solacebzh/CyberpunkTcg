package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.engine.DrawPhaseHandler;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.List;

/**
 * Mini-Feature 5 — le joueur actif choisit le dé Gig à lancer pendant la phase
 * {@code DRAW} (étape {@link DrawStep#AWAITING_DIE_SELECT}).
 *
 * <p>Règle officielle § START PHASE — « GAIN A GIG : Take a die from your fixer
 * area, roll it, and add it to your friendly Gig area. You can choose any die
 * except the d20, which is always rolled last. » Le serveur vérifie donc que le
 * dé est encore dans la Fixer Area du joueur et que le {@code d20} n'est choisi
 * que lorsqu'il est le dernier dé restant, puis lance le dé (tirage serveur,
 * générateur de la partie), range le résultat dans la Gig Area et passe
 * automatiquement en phase {@code MAIN}.</p>
 *
 * <p>Action filaire : {@code SELECT_DIE}, dé transmis dans {@code dice[0]} (ou
 * {@code chosen}). Les identifiants sont normalisés ({@code "D8"} ≡ {@code "d8"}).</p>
 */
public class SelectDieCommand implements GameCommand {

    private final String playerId;
    private final String die;

    public SelectDieCommand(String playerId, String die) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        String normalized = Player.normalizeDie(die);
        if (normalized == null) {
            throw new IllegalArgumentException("Le dé à lancer est obligatoire");
        }
        this.playerId = playerId;
        this.die = normalized;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    /** Dé demandé, normalisé en minuscules ({@code d4}…{@code d20}). */
    public String getDie() {
        return die;
    }

    @Override
    public String actionType() {
        return "SELECT_DIE";
    }

    @Override
    public String describe() {
        return "choisir et lancer le dé Gig " + die;
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        Player player = GameCommand.requireActivePlayer(state, playerId);
        DrawPhaseHandler.requireStep(state, DrawStep.AWAITING_DIE_SELECT);
        if (!isKnownDie(die)) {
            throw new GameRuleException("Dé Gig inconnu : " + die + " (attendu d4, d6, d8, d10, d12 ou d20)");
        }
        if (!player.getFixerDice().contains(die)) {
            throw new GameRuleException("Le dé " + die + " n'est plus dans la Fixer Area (dés restants : "
                    + player.getFixerDice() + ")");
        }
        if (!player.canSelectFixerDie(die)) {
            // Seul cas possible ici : d20 demandé alors qu'il reste d'autres dés.
            throw new GameRuleException("Le d20 se lance toujours en dernier : choisissez d'abord "
                    + player.selectableFixerDice());
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        DrawPhaseHandler.rollSelectedDie(state, playerId, die);
        return GameCommand.eventsSince(state, mark);
    }

    private static boolean isKnownDie(String die) {
        try {
            Player.sidesOf(die);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
