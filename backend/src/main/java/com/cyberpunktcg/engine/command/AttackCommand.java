package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.CombatResolver;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.RuleEngine;
import com.cyberpunktcg.engine.TriggerType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Déclare une attaque avec une Unit prête, vers une Unit rivale <strong>dépensée</strong>
 * ou (sans cible) directement vers la Gig Area adverse.
 *
 * <p>Règles appliquées (Mini-Feature 6 — {@code docs/OFFICIAL-RULES.md} § ATTACKING) :</p>
 * <ul>
 *   <li>l'attaquant est une Unit alliée du Field, prête et sans mal d'invocation
 *   (sauf {@code HASTE}, {@code ADRENALINE} ou {@code GO_SOLO}) ; déclarer
 *   l'attaque l'incline ({@code exhausted = true}) ;</li>
 *   <li>la première attaque fait passer la phase {@code MAIN → COMBAT} ;</li>
 *   <li>chaque attaque ouvre une fenêtre de réaction pour le défenseur
 *   (<strong>QUICK uniquement</strong> pour les cartes) ;</li>
 *   <li>les déclencheurs {@code ON_ATTACK} se résolvent avant le combat ;</li>
 *   <li>cibles valides : le joueur rival (Gig Area) ou une Unit rivale du Field
 *   <em>déjà inclinée</em> (« Ready Units can't be attacked ») ;</li>
 *   <li><strong>BLOCKER au choix du défenseur</strong> : si le défenseur contrôle
 *   un Blocker prêt, l'attaque est suspendue sur la fenêtre « Utiliser Blocker ? »
 *   ({@link com.cyberpunktcg.domain.game.CombatStep#AWAITING_BLOCK}) — il peut
 *   bloquer avec un ou plusieurs Blockers ({@link BlockCommand}) ou renoncer
 *   ({@link DeclineBlockCommand}). Le blocage n'est donc plus imposé à
 *   l'attaquant (comportement antérieur à la Mini-Feature 6) ;</li>
 *   <li><strong>STEAL!</strong> attaque directe non bloquée : quota
 *   {@code N = (power / 10) + 1} (0 si power ≤ 0) <em>plafonné</em> aux dés Gigs
 *   actifs du défenseur ({@code M = min(N, dés actifs)}), puis l'attaquant choisit
 *   les {@code M} dés à voler ({@link StealGigCommand}). Aucun dé n'est créé, la
 *   Fixer Area n'est jamais ponctionnée, et {@code M = 0} n'est pas une erreur :
 *   l'attaque réussit sans rien voler ;</li>
 *   <li><strong>FIGHT!</strong> combat Unité contre Unité : comparaison des
 *   puissances totales (Unit + Gears), à égalité les deux Units sont vaincues
 *   ({@link RuleEngine#fight}). Une attaque redirigée par un Blocker ne vole
 *   jamais de Gig.</li>
 * </ul>
 *
 * <p>Une seule attaque à la fois : tant qu'une attaque est en cours de résolution
 * ({@link GameState#isCombatPending()}), aucune autre ne peut être déclarée.</p>
 */
public class AttackCommand implements GameCommand {

    private final String playerId;
    private final UUID attackerInstanceId;
    private final UUID targetInstanceId;

    /** Attaque directe vers la Gig Area (vol de dés Gigs). */
    public AttackCommand(String playerId, UUID attackerInstanceId) {
        this(playerId, attackerInstanceId, null);
    }

    /** Attaque vers une Unit rivale dépensée. */
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

    @Override
    public String actionType() {
        return "ATTACK";
    }

    @Override
    public String describe() {
        return isGigSteal() ? "attaquer la Gig Area adverse" : "attaquer une Unit adverse";
    }

    @Override
    public String describe(GameState state) {
        String attacker = state.findInstance(attackerInstanceId)
                .map(CardInstance::getName)
                .orElse("une Unit inconnue");
        if (isGigSteal()) {
            return "attaquer la Gig Area adverse avec " + attacker;
        }
        String target = state.findInstance(targetInstanceId)
                .map(CardInstance::getName)
                .orElse("une Unit inconnue");
        return "attaquer " + target + " avec " + attacker;
    }

    public UUID getAttackerInstanceId() {
        return attackerInstanceId;
    }

    public UUID getTargetInstanceId() {
        return targetInstanceId;
    }

    /** {@code true} pour une attaque directe vers la Gig Area (vol de dés). */
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
        if (state.isCombatPending()) {
            // Règle officielle : « Each Unit attacks individually, and completes all
            // the attacking steps before another Unit can attack. »
            throw new GameRuleException("Une attaque est déjà en cours de résolution "
                    + (state.isAwaitingBlock()
                    ? "(le défenseur doit répondre à la fenêtre Blocker)"
                    : "(choisis d'abord les dés Gigs à voler)"));
        }

        CardInstance attacker = player.findIn(Zone.FIELD, attackerInstanceId)
                .orElseThrow(() -> new GameRuleException("Attaquant introuvable sur le Field"));
        if (!attacker.isUnit()) {
            throw new GameRuleException("Seule une Unit peut attaquer");
        }
        if (attacker.isExhausted()) {
            throw new GameRuleException("Cette Unit est déjà épuisée");
        }
        if (attacker.isSummoningSickness() && !attacker.canIgnoreSummoningSickness()) {
            throw new GameRuleException("Cette Unit vient d'être jouée (mal d'invocation)");
        }

        if (isGigSteal()) {
            // Mini-Feature 6 : un Blocker prêt n'interdit plus l'attaque directe —
            // c'est au défenseur de choisir s'il bloque. Un défenseur sans dé Gig
            // actif non plus : l'attaque réussit et ne vole rien (plafond strict M = 0).
            return;
        }

        Player rival = state.getOpponent(playerId);
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
        if (!target.isExhausted()) {
            // Règle officielle : « Ready Units can't be attacked. »
            throw new GameRuleException("Cette Unit rivale est prête : on n'attaque qu'une Unit déjà "
                    + "inclinée (dépensée) — un Blocker prêt intercepte via la fenêtre de réaction");
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
        int attackerPower = state.totalPowerFor(attacker);
        CardInstance declaredTarget = null;
        String targetName = null;
        if (!isGigSteal()) {
            Optional<CardInstance> declared = state.findInstance(targetInstanceId);
            if (declared.isPresent()) {
                declaredTarget = declared.get();
                targetName = declaredTarget.getName();
            } else {
                targetName = "cible disparue";
            }
        }
        if (isGigSteal()) {
            state.appendEvent(GameEventType.ATTACK_DECLARED, playerId,
                    "attaque directe vers la Gig Area (" + attacker.getName() + ")");
        } else {
            state.appendEvent(GameEventType.ATTACK_DECLARED, playerId,
                    "attaque déclarée (" + attacker.getName() + ")");
        }
        state.logSuccess(playerId, actionType(),
                "Joueur " + playerId + " attaque avec " + attacker.getName() + " (power "
                        + attackerPower + ") → cible: "
                        + (isGigSteal() ? "Gig Area du rival" : targetName),
                GameLog.details("attacker", attacker.getName(), "attackerId", attacker.getCardId(),
                        "power", attackerPower,
                        "quota", engine.calculateQuota(attackerPower),
                        "target", isGigSteal() ? "GIG_AREA" : targetName,
                        "targetInstanceId", targetInstanceId == null ? null : targetInstanceId.toString(),
                        "defender", rival.getId(),
                        "readyBlockers", rival.readyBlockers().size()));

        state.openReactionWindow(rival.getId(), attacker.getInstanceId().toString());
        state.appendEvent(GameEventType.REACTION_WINDOW_OPENED, rival.getId(),
                "fenêtre de réaction ouverte (QUICK uniquement)");
        state.logInfo(rival.getId(), "REACTION_WINDOW",
                "Fenêtre de réaction ouverte pour " + rival.getId()
                        + " (cartes QUICK uniquement ; " + rival.getHand().size() + " carte(s) en main"
                        + (rival.controlsReadyBlocker() ? " ; Blocker(s) prêt(s) : "
                        + rival.readyBlockers().size() + " — fenêtre « Utiliser Blocker ? »" : "")
                        + ")",
                GameLog.details("attacker", attacker.getName(), "rule", "QUICK_ONLY",
                        "readyBlockers", rival.readyBlockers().size()));

        engine.resolveEffects(state, attacker, TriggerType.ON_ATTACK, declaredTarget);
        if (state.isGameOver()) {
            return GameCommand.eventsSince(state, mark);
        }

        // Mini-Feature 6 : la résolution est interactive — blocage éventuel du
        // défenseur, puis combat ou choix des dés Gigs à voler (plafond strict).
        CombatResolver.openAttack(state, attacker, declaredTarget);
        return GameCommand.eventsSince(state, mark);
    }
}
