package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.CombatStep;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.GigDie;
import com.cyberpunktcg.domain.game.PendingAttack;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Machine à états du combat (Mini-Feature 6) — partagée par {@code AttackCommand},
 * {@code BlockCommand}, {@code DeclineBlockCommand}, {@code StealGigCommand} et
 * {@code EndTurnCommand}.
 *
 * <p>Règles officielles § ATTACKING : « Each Unit attacks individually, and
 * completes all the attacking steps before another Unit can attack. » Une
 * attaque n'est donc plus résolue d'un seul bloc ; elle passe par les étapes
 * interactives portées par {@link PendingAttack} :</p>
 *
 * <ol>
 *   <li><strong>REACT</strong> — si le défenseur contrôle un {@code BLOCKER}
 *   prêt, la fenêtre « Utiliser Blocker ? » s'ouvre
 *   ({@link CombatStep#AWAITING_BLOCK}) : il peut dépenser <em>un ou plusieurs</em>
 *   Blockers ({@link #resolveBlock}) ou renoncer ({@link #resolveAttack}) ;</li>
 *   <li><strong>FIGHT!</strong> — comparaison des puissances ({@link RuleEngine#fight}).
 *   Blocage multiple : chaque Blocker est incliné et résout ses compétences, mais
 *   <em>seul le dernier Blocker déclaré</em> encaisse les dégâts du combat ;</li>
 *   <li><strong>STEAL!</strong> — attaque directe non bloquée : quota
 *   {@code N = (power / 10) + 1} (0 si power ≤ 0) plafonné aux dés Gigs
 *   <em>actifs</em> du défenseur ({@code M = min(N, dés actifs)}), puis choix des
 *   {@code M} dés par l'attaquant ({@link #chooseStolenDice}). Une attaque
 *   redirigée par un Blocker ne vole <strong>jamais</strong> de Gig.</li>
 * </ol>
 *
 * <p>Classe utilitaire : aucune règle ne vit dans les commandes, qui ne font que
 * valider puis déléguer ici.</p>
 */
public final class CombatResolver {

    private CombatResolver() {
        // Classe utilitaire non instanciable.
    }

    /**
     * Ouvre la résolution d'une attaque déclarée (Unité déjà inclinée,
     * déclencheurs {@code ON_ATTACK} déjà résolus par {@code AttackCommand}).
     *
     * <p>Si le défenseur contrôle au moins un {@code BLOCKER} prêt, l'attaque est
     * suspendue sur {@link CombatStep#AWAITING_BLOCK} ; sinon elle est résolue
     * immédiatement ({@link #resolveAttack}).</p>
     *
     * @param declaredTarget cible déclarée, {@code null} pour une attaque directe
     *                       vers la Gig Area adverse
     */
    public static void openAttack(GameState state, CardInstance attacker, CardInstance declaredTarget) {
        if (state.isGameOver() || attacker == null) {
            return;
        }
        Player defender = state.getOpponent(attacker.getOwnerId());
        PendingAttack pending = new PendingAttack(attacker.getOwnerId(), defender.getId(),
                attacker.getInstanceId(), declaredTarget == null ? null : declaredTarget.getInstanceId());
        state.setPendingAttack(pending);

        List<CardInstance> blockers = defender.readyBlockers();
        if (!blockers.isEmpty()) {
            pending.setStep(CombatStep.AWAITING_BLOCK);
            List<String> names = new ArrayList<String>(blockers.size());
            List<String> ids = new ArrayList<String>(blockers.size());
            for (CardInstance blocker : blockers) {
                names.add(blocker.getName());
                ids.add(blocker.getInstanceId().toString());
            }
            state.logInfo(defender.getId(), "BLOCKER_PROMPT",
                    "Utiliser Blocker ? Joueur " + defender.getId() + " peut intercepter avec "
                            + blockers.size() + " Blocker(s) prêt(s) : " + names
                            + " (blocage multiple autorisé — seul le dernier Blocker encaisse les dégâts)",
                    GameLog.details("defender", defender.getId(), "blockers", names,
                            "blockerInstanceIds", ids,
                            "attacker", attacker.getName(),
                            "direct", declaredTarget == null));
            return;
        }
        resolveAttack(state);
    }

    /**
     * Résout l'attaque après la fenêtre de réaction : combat contre la cible
     * déclarée, ou vol de dés (quota plafonné) pour une attaque directe.
     *
     * <p>Appelé par {@code DeclineBlockCommand} (renoncement explicite), par
     * {@code AttackCommand} (aucun Blocker prêt) et par {@link #autoResolve}
     * (fin de tour).</p>
     */
    public static void resolveAttack(GameState state) {
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || state.isGameOver()) {
            return;
        }
        CardInstance attacker = onField(state, pending.getAttackerPlayerId(), pending.getAttackerInstanceId());
        if (attacker == null) {
            fizzle(state, pending, "l'Unité attaquante a quitté le Field");
            return;
        }
        RuleEngine engine = new RuleEngine();

        if (!pending.isDirect()) {
            CardInstance target = onField(state, pending.getDefendingPlayerId(), pending.getTargetInstanceId());
            if (target == null) {
                fizzle(state, pending, "la cible a quitté le Field");
                return;
            }
            engine.fight(state, attacker, target);
            state.clearPendingAttack();
            return;
        }

        Player defender = state.getPlayer(pending.getDefendingPlayerId());
        int power = state.totalPowerFor(attacker);
        int quota = engine.calculateQuota(power);
        int stealable = engine.calculateActualStealable(power, defender.getActiveGigCount());

        if (stealable == 0) {
            // Plafond strict : power 0, ou aucun dé Gig actif chez le défenseur.
            // L'attaque reste réussie (Unité inclinée, phase Combat), rien n'est volé.
            pending.prepareStealChoice(quota, 0);
            state.appendEvent(GameEventType.ATTACK_DECLARED, pending.getAttackerPlayerId(),
                    "attaque directe sans vol (quota " + quota + ", "
                            + defender.getActiveGigCount() + " dé(s) Gig actif(s) chez le défenseur)");
            state.logFailed(pending.getAttackerPlayerId(), "GIG_STOLEN",
                    "Attaque directe sans vol de Gig : quota N = " + quota + " (power " + power
                            + ") plafonné à M = 0 — le défenseur n'a " + defender.getActiveGigCount()
                            + " dé(s) Gig actif(s) (les dés non lancés de sa Fixer Area ne sont jamais volés)",
                    GameLog.details("power", power, "quota", quota, "stealable", 0,
                            "activeGigs", defender.getActiveGigCount(),
                            "fixerDice", defender.getFixerDice().size()));
            state.clearPendingAttack();
            return;
        }

        pending.prepareStealChoice(quota, stealable);
        List<String> dieIds = new ArrayList<String>(stealable);
        for (GigDie die : defender.activeGigs()) {
            dieIds.add(die.id());
        }
        state.logInfo(pending.getAttackerPlayerId(), "GIG_STEAL_CHOICE",
                "Vol de Gigs : quota N = " + quota + " (power " + power + "), plafond strict M = "
                        + stealable + " dé(s) à choisir parmi les " + defender.getActiveGigCount()
                        + " dés Gigs actifs du défenseur",
                GameLog.details("power", power, "quota", quota, "stealable", stealable,
                        "activeGigs", defender.getActiveGigCount(), "dieIds", dieIds));
    }

    /**
     * Le défenseur intercepte avec un ou plusieurs {@code BLOCKER} : tous sont
     * inclinés, les compétences de CHACUN sont résolues (déclencheurs
     * {@code ON_BLOCK} puis {@code ON_ATTACK}) dans l'ordre de déclaration, et
     * <strong>seul le dernier Blocker</strong> encaisse les dégâts du combat.
     *
     * <p>Une attaque redirigée ne vole jamais de Gig, même si le Blocker est
     * vaincu (règle officielle § ATTACKING).</p>
     */
    public static void resolveBlock(GameState state, List<CardInstance> blockers) {
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || state.isGameOver() || blockers == null || blockers.isEmpty()) {
            return;
        }
        RuleEngine engine = new RuleEngine();
        CardInstance attacker = onField(state, pending.getAttackerPlayerId(), pending.getAttackerInstanceId());
        CardInstance last = null;

        for (CardInstance blocker : blockers) {
            blocker.setExhausted(true);
            pending.addBlocker(blocker.getInstanceId());
            state.appendEvent(GameEventType.ATTACK_BLOCKED, blocker.getOwnerId(),
                    "Blocker " + blocker.getName() + " intercepte l'attaque (redirection)");
            state.logSuccess(blocker.getOwnerId(), "USE_BLOCKER",
                    "Joueur " + blocker.getOwnerId() + " bloque avec " + blocker.getName()
                            + " (Unité inclinée, attaque redirigée vers elle)",
                    GameLog.details("blocker", blocker.getName(), "blockerCardId", blocker.getCardId(),
                            "blockerInstanceId", blocker.getInstanceId().toString(),
                            "attackerPlayer", pending.getAttackerPlayerId(),
                            "redirectedFrom", pending.isDirect() ? "GIG_AREA" : "UNIT"));
            // Chaque compétence du Blocker se résout (ON_BLOCK, puis ON_ATTACK — il entre au combat).
            engine.resolveEffects(state, blocker, TriggerType.ON_BLOCK, attacker);
            if (state.isGameOver()) {
                state.clearPendingAttack();
                return;
            }
            engine.resolveEffects(state, blocker, TriggerType.ON_ATTACK, attacker);
            if (state.isGameOver()) {
                state.clearPendingAttack();
                return;
            }
            last = blocker;
        }

        attacker = onField(state, pending.getAttackerPlayerId(), pending.getAttackerInstanceId());
        if (attacker == null || last == null || last.getZone() != Zone.FIELD) {
            fizzle(state, pending, "le combat n'a plus de participants sur le Field");
            return;
        }
        engine.fight(state, attacker, last);
        // Attaque redirigée : aucun Gig volé pour cette attaque.
        state.clearPendingAttack();
    }

    /**
     * Transfère les dés Gigs choisis par l'attaquant (plafond strict déjà validé
     * par {@code StealGigCommand}) : chaque dé conserve son identifiant, son type
     * et sa valeur exacte.
     *
     * @return les dés effectivement volés, dans l'ordre du choix
     */
    public static List<GigDie> chooseStolenDice(GameState state, List<String> dieIds) {
        List<GigDie> stolen = new ArrayList<GigDie>();
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || dieIds == null || dieIds.isEmpty()) {
            state.clearPendingAttack();
            return stolen;
        }
        Player thief = state.getPlayer(pending.getAttackerPlayerId());
        Player victim = state.getPlayer(pending.getDefendingPlayerId());
        StringBuilder values = new StringBuilder();
        for (String dieId : dieIds) {
            Optional<GigDie> transferred = state.stealGig(victim.getId(), thief.getId(), dieId);
            if (!transferred.isPresent()) {
                continue;
            }
            GigDie die = transferred.get();
            stolen.add(die);
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append(die.die()).append(" → ").append(die.value());
            state.appendEvent(GameEventType.GIG_STOLEN, thief.getId(),
                    "vol d'un Gig " + die.die() + " → " + die.value()
                            + " (total " + thief.getGigCount() + ")");
        }
        state.logSuccess(thief.getId(), "GIG_STOLEN",
                "Joueur " + thief.getId() + " vole " + stolen.size() + " dé(s) Gig : " + values
                        + " (total " + thief.getGigCount() + " Gigs, quota " + pending.getQuota()
                        + ", plafond " + pending.getStealable() + ")",
                GameLog.details("count", stolen.size(), "dice", values.toString(),
                        "quota", pending.getQuota(), "stealable", pending.getStealable(),
                        "gigsTotal", thief.getGigCount(), "rivalGigs", victim.getGigCount()));
        state.clearPendingAttack();
        return stolen;
    }

    /**
     * Résout automatiquement une attaque encore en attente — appelé par
     * {@code EndTurnCommand} pour qu'un défenseur silencieux ne bloque pas la
     * partie : blocage refusé implicitement, puis vol des {@code M} dés les plus
     * forts (choix déterministe).
     *
     * @param reason libellé journalisé (« Fin de tour », …)
     */
    public static void autoResolve(GameState state, String reason) {
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || state.isGameOver()) {
            return;
        }
        if (pending.getStep() == CombatStep.AWAITING_BLOCK) {
            state.logInfo(pending.getDefendingPlayerId(), "DECLINE_BLOCK",
                    reason + " : le défenseur n'a pas utilisé de Blocker, l'attaque suit son cours",
                    GameLog.details("defender", pending.getDefendingPlayerId(), "auto", true));
            resolveAttack(state);
            pending = state.getPendingAttack();
            if (pending == null || state.isGameOver()) {
                return;
            }
        }

        Player defender = state.getPlayer(pending.getDefendingPlayerId());
        CardInstance attacker = onField(state, pending.getAttackerPlayerId(), pending.getAttackerInstanceId());
        int power = attacker == null ? 0 : state.totalPowerFor(attacker);
        RuleEngine engine = new RuleEngine();
        int stealable = engine.calculateActualStealable(power, defender.getActiveGigCount());
        List<GigDie> candidates = new ArrayList<GigDie>(defender.activeGigs());
        // Tri stable par valeur décroissante : à défaut de choix du joueur, on
        // vole les dés les plus forts (comportement historique, déterministe).
        Collections.sort(candidates, (left, right) -> Integer.compare(right.value(), left.value()));
        List<String> chosen = new ArrayList<String>(stealable);
        for (int i = 0; i < stealable && i < candidates.size(); i++) {
            chosen.add(candidates.get(i).id());
        }
        state.logInfo(pending.getAttackerPlayerId(), "GIG_STEAL_AUTO",
                reason + " : vol de " + chosen.size() + " dé(s) Gig résolu automatiquement"
                        + " (quota " + engine.calculateQuota(power) + ", plafond " + stealable + ")",
                GameLog.details("power", power, "quota", engine.calculateQuota(power),
                        "stealable", stealable, "chosen", chosen.size(), "auto", true));
        chooseStolenDice(state, chosen);
    }

    /** Nombre de dés Gigs actuellement volables pour l'attaque en cours (0 sinon). */
    public static int stealableNow(GameState state) {
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || pending.getStep() != CombatStep.AWAITING_STEAL_CHOICE) {
            return 0;
        }
        CardInstance attacker = onField(state, pending.getAttackerPlayerId(), pending.getAttackerInstanceId());
        int power = attacker == null ? 0 : state.totalPowerFor(attacker);
        Player defender = state.getPlayer(pending.getDefendingPlayerId());
        return new RuleEngine().calculateActualStealable(power, defender.getActiveGigCount());
    }

    // ------------------------------------------------------------------
    // Aides internes
    // ------------------------------------------------------------------

    /** Exemplaire encore présent sur le Field d'un joueur, {@code null} sinon. */
    private static CardInstance onField(GameState state, String playerId, UUID instanceId) {
        if (instanceId == null || playerId == null || !state.hasPlayer(playerId)) {
            return null;
        }
        Optional<CardInstance> found = state.getPlayer(playerId).findIn(Zone.FIELD, instanceId);
        return found.orElse(null);
    }

    /** Attaque sans effet : plus de participant, rien à résoudre. */
    private static void fizzle(GameState state, PendingAttack pending, String cause) {
        state.appendEvent(GameEventType.ATTACK_DECLARED, pending.getAttackerPlayerId(),
                "attaque sans effet (" + cause + ")");
        state.logFailed(pending.getAttackerPlayerId(), "ATTACK",
                "Attaque sans effet : " + cause,
                GameLog.details("cause", cause, "attacker", pending.getAttackerPlayerId(),
                        "defender", pending.getDefendingPlayerId()));
        state.clearPendingAttack();
    }
}
