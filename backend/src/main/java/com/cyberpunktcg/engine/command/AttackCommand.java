package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.RuleEngine;
import com.cyberpunktcg.engine.TriggerType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Déclare une attaque avec une Unit prête, vers une Unit rivale ou (sans cible)
 * directement vers la Gig Area adverse pour voler un Gig.
 *
 * <p>Règles appliquées :</p>
 * <ul>
 *   <li>l'attaquant est une Unit alliée du Field, prête et sans mal d'invocation
 *   (sauf {@code GO_SOLO}) ; attaquer l'épuise ;</li>
 *   <li>la première attaque fait passer la phase {@code MAIN → COMBAT} ;</li>
 *   <li>chaque attaque ouvre une fenêtre de réaction pour le défenseur
 *   (<strong>QUICK uniquement</strong> ; seules les cartes avec le mot-clé quick peuvent être jouées en réaction) ;</li>
 *   <li>les déclencheurs {@code ON_ATTACK} se résolvent avant le combat ;</li>
 *   <li><strong>BLOCKER intercepte</strong> : si un BLOCKER rival prêt existe, l'attaquant doit le cibler (interception) et le vol direct de Gig est interdit ;</li>
 *   <li>le combat compare les puissances totales (Unit + Gears) : à égalité,
 *   les deux Units sont vaincues ;</li>
 *   <li>un {@code BLOCKER} rival prêt doit être ciblé (interception) et interdit
 *   le vol direct de Gig.</li>
 * </ul>
 */
public class AttackCommand implements GameCommand {

    private final String playerId;
    private final UUID attackerInstanceId;
    private final UUID targetInstanceId;

    /** Attaque directe vers la Gig Area (vol de Gig). */
    public AttackCommand(String playerId, UUID attackerInstanceId) {
        this(playerId, attackerInstanceId, null);
    }

    /** Attaque vers une Unit rivale. */
    public AttackCommand(String playerId, UUID attackerInstanceId, UUID targetInstanceId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        if (attackerInstanceId == null) {
            throw new IllegalArgumentException("L'attaquant est obligatoire");
        }
        this.playerId = playerId;
        this.attackerInstanceId = attackerInstanceId;
        this.targetInstanceId = targetInstanceId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    public UUID getAttackerInstanceId() {
        return attackerInstanceId;
    }

    public UUID getTargetInstanceId() {
        return targetInstanceId;
    }

    /** {@code true} pour un vol de Gig direct (aucune cible désignée). */
    public boolean isGigSteal() {
        return targetInstanceId == null;
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        Player player = GameCommand.requireActivePlayer(state, playerId);
        Phase phase = state.getPhase();
        if (phase != Phase.MAIN && phase != Phase.COMBAT) {
            throw new GameRuleException("On n'attaque qu'en phase Main ou Combat");
        }

        CardInstance attacker = player.findIn(Zone.FIELD, attackerInstanceId)
                .orElseThrow(() -> new GameRuleException("Attaquant introuvable sur le Field"));
        if (!attacker.isUnit()) {
            throw new GameRuleException("Seule une Unit peut attaquer");
        }
        if (attacker.isExhausted()) {
            throw new GameRuleException("Cette Unit est déjà épuisée");
        }
        if (attacker.isSummoningSickness() && !attacker.hasGoSolo()) {
            throw new GameRuleException("Cette Unit vient d'être jouée (mal d'invocation)");
        }

        Player rival = state.getOpponent(playerId);
        if (isGigSteal()) {
            if (rival.controlsReadyBlocker()) {
                throw new GameRuleException("Vol de Gig intercepté : un BLOCKER rival doit être attaqué d'abord");
            }
            if (rival.getGigCount() == 0) {
                throw new GameRuleException("Le rival ne contrôle aucun Gig à voler");
            }
            return;
        }

        Optional<CardInstance> lookup = state.findInstance(targetInstanceId);
        if (!lookup.isPresent()) {
            throw new GameRuleException("Cible introuvable");
        }
        CardInstance target = lookup.get();
        if (target.getZone() != Zone.FIELD || !target.isUnit()) {
            throw new GameRuleException("On n'attaque qu'une Unit rivale du Field");
        }
        if (!target.getOwnerId().equals(rival.getId())) {
            throw new GameRuleException("On n'attaque pas ses propres Units");
        }
        if (rival.controlsReadyBlocker() && !target.isBlocker()) {
            throw new GameRuleException("Un BLOCKER rival doit intercepter cette attaque");
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player player = state.getPlayer(playerId);
        Player rival = state.getOpponent(playerId);
        CardInstance attacker = player.findIn(Zone.FIELD, attackerInstanceId).get();
        RuleEngine engine = new RuleEngine();

        if (state.getPhase() == Phase.MAIN) {
            state.setPhase(Phase.COMBAT);
            state.appendEvent(GameEventType.PHASE_CHANGED, playerId, "phase Combat");
        }

        attacker.setExhausted(true);
        if (isGigSteal()) {
            state.appendEvent(GameEventType.ATTACK_DECLARED, playerId,
                    "attaque directe vers la Gig Area (" + attacker.getName() + ")");
        } else {
            state.appendEvent(GameEventType.ATTACK_DECLARED, playerId,
                    "attaque déclarée (" + attacker.getName() + ")");
        }

        state.openReactionWindow(rival.getId(), attacker.getInstanceId().toString());
        state.appendEvent(GameEventType.REACTION_WINDOW_OPENED, rival.getId(),
                "fenêtre de réaction ouverte (QUICK uniquement)");

        CardInstance target = null;
        if (!isGigSteal()) {
            target = state.findInstance(targetInstanceId).get();
        }
        engine.resolveEffects(state, attacker, TriggerType.ON_ATTACK, target);
        if (state.isGameOver()) {
            return GameCommand.eventsSince(state, mark);
        }

        if (isGigSteal()) {
            Optional<Integer> stolen = state.stealGig(rival.getId(), playerId);
            if (stolen.isPresent()) {
                state.appendEvent(GameEventType.GIG_STOLEN, playerId,
                        "vol d'un Gig de valeur " + stolen.get()
                                + " (total " + player.getGigCount() + ")");
            } else {
                state.appendEvent(GameEventType.ATTACK_DECLARED, playerId,
                        "vol de Gig sans effet (plus aucun Gig adverse)");
            }
            return GameCommand.eventsSince(state, mark);
        }

        Optional<CardInstance> stillThere = rival.findIn(Zone.FIELD, target.getInstanceId());
        if (!stillThere.isPresent()) {
            state.appendEvent(GameEventType.ATTACK_DECLARED, playerId,
                    "attaque sans effet (la cible a quitté le Field)");
            return GameCommand.eventsSince(state, mark);
        }
        CardInstance defender = stillThere.get();
        int attackPower = state.totalPowerFor(attacker);
        int defensePower = state.totalPowerFor(defender);
        if (attackPower > defensePower) {
            engine.defeatUnit(state, defender);
        } else if (attackPower < defensePower) {
            engine.defeatUnit(state, attacker);
        } else {
            engine.defeatUnit(state, defender);
            engine.defeatUnit(state, attacker);
        }
        return GameCommand.eventsSince(state, mark);
    }
}
