package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CombatStep;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.PendingAttack;
import com.cyberpunktcg.engine.CombatResolver;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.List;

/**
 * Mini-Feature 6 (règle B) — le défenseur <strong>renonce</strong> à intercepter
 * avec un {@code {Blocker}} : l'attaque suit son cours (combat contre la cible
 * déclarée, ou vol de dés Gigs plafonné pour une attaque directe).
 *
 * <p>Action filaire : {@code DECLINE_BLOCK}. Sans réponse du défenseur, la fin de
 * tour résout l'attaque automatiquement ({@link CombatResolver#autoResolve}) :
 * un défenseur silencieux ne peut donc pas bloquer la partie.</p>
 */
public class DeclineBlockCommand implements GameCommand {

    private final String playerId;

    public DeclineBlockCommand(String playerId) {
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
        return "DECLINE_BLOCK";
    }

    @Override
    public String describe() {
        return "renoncer à bloquer l'attaque";
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        GameCommand.requireKnownPlayer(state, playerId);
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || pending.getStep() != CombatStep.AWAITING_BLOCK) {
            throw new GameRuleException("Aucune attaque à bloquer : la fenêtre « Utiliser Blocker ? » "
                    + "n'est pas ouverte");
        }
        if (!pending.getDefendingPlayerId().equals(playerId)) {
            throw new GameRuleException("Seul le défenseur (" + pending.getDefendingPlayerId()
                    + ") peut renoncer à bloquer");
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        PendingAttack pending = state.getPendingAttack();
        state.logInfo(playerId, actionType(),
                "Joueur " + playerId + " renonce à bloquer (aucun Blocker dépensé) : l'attaque suit son cours",
                GameLog.details("defender", playerId,
                        "direct", pending.isDirect(),
                        "readyBlockers", state.getPlayer(playerId).readyBlockers().size()));
        CombatResolver.resolveAttack(state);
        return GameCommand.eventsSince(state, mark);
    }
}
