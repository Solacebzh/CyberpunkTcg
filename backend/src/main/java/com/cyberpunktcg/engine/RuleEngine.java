package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameActionResult;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLogEntry;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Moteur de règles : interprète les capacités des cartes et résout les effets.
 *
 * <p>Classe pure (aucune dépendance Spring ou JPA), instanciée à la demande par
 * les commandes. Le routage effet → handler suit le pattern Strategy
 * (voir {@link EffectHandler}) : ajouter un effet = ajouter une entrée dans
 * {@link #handlers} + une valeur à {@link EffectType}. Voir
 * {@code docs/RULE-ENGINE.md} pour le guide complet.</p>
 *
 * <p>Note : la condition de victoire (7 Gigs au début du tour) est gérée uniquement
 * dans {@link com.cyberpunktcg.engine.command.EndTurnCommand}, jamais en continu ici.</p>
 *
 * <p>Les déclencheurs peuvent cascader (un {@code ON_DEATH} peut piocher, une
 * pioche sur deck vide termine la partie…) : la profondeur est bornée par
 * {@link GameConstants#MAX_TRIGGER_DEPTH}.</p>
 */
public class RuleEngine {

    private final Map<EffectType, EffectHandler> handlers;
    private int triggerDepth;

    public RuleEngine() {
        Map<EffectType, EffectHandler> registered = new EnumMap<EffectType, EffectHandler>(EffectType.class);
        registered.put(EffectType.DAMAGE, new DamageHandler());
        registered.put(EffectType.HEAL, new HealHandler());
        registered.put(EffectType.DRAW, new DrawHandler());
        registered.put(EffectType.GRANT_POWER, new GrantPowerHandler());
        registered.put(EffectType.STEAL_GIG, new StealGigHandler());
        registered.put(EffectType.REDUCE_COST, new ReduceCostHandler());
        registered.put(EffectType.DEFEAT_UNIT, new DefeatUnitHandler());
        registered.put(EffectType.BOOST_GIG, new BoostGigHandler());
        registered.put(EffectType.REDUCE_GIG, new ReduceGigHandler());
        registered.put(EffectType.DISCARD, new DiscardHandler());
        registered.put(EffectType.BUFF, new BuffHandler());
        this.handlers = Collections.unmodifiableMap(registered);
        this.triggerDepth = 0;
    }

    /**
     * Résout tous les effets d'une source correspondant à un déclencheur,
     * dans l'ordre d'impression des capacités.
     *
     * @param state  état de partie (muté)
     * @param source carte source (capacités + contrôleur)
     * @param trigger déclencheur à résoudre
     * @param target cible désignée (peut être {@code null})
     */
    public void resolveEffects(GameState state, CardInstance source, TriggerType trigger, CardInstance target) {
        if (state.isGameOver()) {
            return;
        }
        if (triggerDepth >= GameConstants.MAX_TRIGGER_DEPTH) {
            state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                    "cascade d'effets interrompue (profondeur maximale atteinte)");
            return;
        }
        List<GameEffect> effects = EffectParser.parseAbilities(source);
        triggerDepth++;
        try {
            for (GameEffect effect : effects) {
                if (effect.getTrigger() == trigger) {
                    resolveEffect(state, source, effect, target);
                    if (state.isGameOver()) {
                        return;
                    }
                }
            }
        } finally {
            triggerDepth--;
        }
    }

    /**
     * Résout UN effet : route vers le handler du type d'effet.
     *
     * @param state  état de partie (muté)
     * @param source carte à l'origine de l'effet
     * @param effect effet à appliquer
     * @param target cible désignée (peut être {@code null})
     */
    public void resolveEffect(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
        if (state.isGameOver()) {
            return;
        }
        EffectHandler handler = handlers.get(effect.getType());
        if (handler == null) {
            state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                    "effet non implémenté : " + effect.getType());
            state.logFailed(source.getOwnerId(), "EFFECT", "Effet non implémenté : " + effect.getType(),
                    detail("effect", effect.getType()));
            return;
        }
        handler.apply(state, source, effect, target);
        state.logSuccess(source.getOwnerId(), "EFFECT",
                "Effet résolu : " + source.getName() + " → " + effect,
                detail("source", source.getName(), "effect", effect.toString(),
                        "target", target == null ? null : target.getName()));
    }

    /**
     * Vainc une Unit sur le Field : défausse (+ Gears attachés, qui suivent leur
     * hôte), puis déclencheurs {@code ON_DEATH} de la victime. Sans effet si la
     * carte a déjà quitté le Field (garde anti double résolution).
     */
    public void defeatUnit(GameState state, CardInstance unit) {
        if (state.isGameOver()) {
            return;
        }
        Optional<Player> owner = state.findInstanceOwner(unit.getInstanceId());
        if (!owner.isPresent()) {
            return;
        }
        CardInstance actual = null;
        Optional<CardInstance> lookup = owner.get().findIn(Zone.FIELD, unit.getInstanceId());
        if (lookup.isPresent()) {
            actual = lookup.get();
        }
        if (actual == null) {
            return;
        }
        List<CardInstance> followers = new ArrayList<CardInstance>();
        for (UUID attachmentId : actual.getAttachments()) {
            Optional<CardInstance> attachment = state.findInstance(attachmentId);
            if (attachment.isPresent() && attachment.get().getZone() == Zone.FIELD) {
                followers.add(attachment.get());
            }
        }
        for (CardInstance follower : followers) {
            follower.setAttachedTo(null);
            follower.clearCombatMarkers();
            state.getPlayer(follower.getOwnerId()).moveToZone(follower, Zone.TRASH);
        }
        actual.getAttachments().clear();
        actual.setAttachedTo(null);
        actual.clearCombatMarkers();
        owner.get().moveToZone(actual, Zone.TRASH);
        state.appendEvent(GameEventType.UNIT_DEFEATED, owner.get().getId(),
                "unit vaincue (" + followers.size() + " gear(s) défaussé(s) avec elle)");
        state.logSuccess(owner.get().getId(), "UNIT_DEFEATED",
                "Unit vaincue : " + actual.getName() + " (propriétaire " + owner.get().getId() + ")",
                detail("cardId", actual.getCardId(), "gears", followers.size()));
        resolveEffects(state, actual, TriggerType.ON_DEATH, null);
    }

    // ------------------------------------------------------------------
    // Mini-Feature 6 — Combat & vol de dés (quota + plafond strict)
    // ------------------------------------------------------------------

    /**
     * Quota théorique de Gigs volés par une attaque directe (règle officielle
     * § ATTACKING — « Units steal an extra Gig for every 10 power (and 0 Gigs at
     * power 0) ») :
     *
     * <ul>
     *   <li>puissance ≤ 0 → <strong>0</strong> Gig ;</li>
     *   <li>puissance ≥ 1 → {@code N = (power / 10) + 1} : 1 à 9 → 1 Gig,
     *   10 à 19 → 2 Gigs, 20 à 29 → 3 Gigs, 30 à 39 → 4 Gigs, <em>etc.</em></li>
     * </ul>
     *
     * @param unitPower puissance totale de l'attaquant (Unit + Gears attachés)
     * @return le quota théorique {@code N}, jamais négatif
     */
    public int calculateQuota(int unitPower) {
        if (unitPower <= 0) {
            return 0;
        }
        return (unitPower / GameConstants.POWER_PER_EXTRA_GIG) + 1;
    }

    /**
     * Nombre de dés Gigs <strong>réellement volables</strong> — règle du plafond
     * strict (Mini-Feature 6) : on ne vole que les dés déjà lancés (actifs) de la
     * Gig Area du défenseur, jamais un dé de sa Fixer Area, et on ne crée jamais
     * de dé. {@code M = min(N, dés actifs du défenseur)}.
     *
     * @param unitPower      puissance totale de l'attaquant
     * @param activeGigsCount nombre de dés Gigs actifs du défenseur
     * @return {@code M}, le nombre de dés à choisir (0 si aucun dé actif)
     */
    public int calculateActualStealable(int unitPower, int activeGigsCount) {
        int quota = calculateQuota(unitPower);
        return Math.min(quota, Math.max(0, activeGigsCount));
    }

    /**
     * Combat Unité contre Unité (règle officielle § ATTACKING — « FIGHT! Compare
     * both Units' power ») : l'attaquant inflige des dégâts égaux à sa puissance
     * totale (Unit + Gears attachés) ; la cible est vaincue dès que ces dégâts
     * atteignent ou dépassent sa puissance, et à égalité les deux Units se
     * vainquent. Les Units vaincues partent en {@code TRASH} avec leurs Gears
     * (voir {@link #defeatUnit}).
     *
     * @return le libellé du verdict, journalisé par ailleurs
     */
    public String fight(GameState state, CardInstance attacker, CardInstance defender) {
        if (state.isGameOver() || attacker == null || defender == null) {
            return null;
        }
        int attackPower = state.totalPowerFor(attacker);
        int defensePower = state.totalPowerFor(defender);
        String outcome;
        if (attackPower > defensePower) {
            outcome = attacker.getName() + " l'emporte (" + attackPower + " > " + defensePower + ")";
            defeatUnit(state, defender);
        } else if (attackPower < defensePower) {
            outcome = defender.getName() + " résiste (" + defensePower + " > " + attackPower + ")";
            defeatUnit(state, attacker);
        } else {
            outcome = "égalité à " + attackPower + " : les deux Units sont vaincues";
            defeatUnit(state, defender);
            defeatUnit(state, attacker);
        }
        state.log(attacker.getOwnerId(), "FIGHT",
                "Combat : " + attacker.getName() + " (" + attackPower + ") vs " + defender.getName()
                        + " (" + defensePower + ") → " + outcome,
                GameActionResult.SUCCESS,
                detail("attacker", attacker.getName(), "attackerPower", attackPower,
                        "defender", defender.getName(), "defenderPower", defensePower,
                        "outcome", outcome));
        return outcome;
    }

    /**
     * Résout la cible d'un effet en liste de cartes (effets visant des cartes).
     * {@code SELF} désigne toujours la source (quelle que soit sa zone, pour que
     * les effets {@code FLIP} des Legends fonctionnent) ; les autres cibles
     * doivent être sur le Field. Les effets visant des joueurs (pioche, vol,
     * remise) lisent le contrôleur et ignorent cette méthode.
     */
    List<CardInstance> resolveUnitTargets(GameState state, CardInstance source,
                                         GameEffect effect, CardInstance target) {
        switch (effect.getTarget()) {
            case SELF:
                return Collections.singletonList(source);
            case TARGET_UNIT:
                if (target == null || target.getZone() != Zone.FIELD) {
                    return Collections.emptyList();
                }
                List<CardInstance> single = new ArrayList<CardInstance>(1);
                single.add(target);
                return single;
            case EACH_RIVAL_UNIT:
                return rivalUnitsOnField(state, source);
            case FRIENDLY_UNIT:
                return strongestFriendlyUnit(state, source);
            case RIVAL_UNIT:
                return strongestRivalUnit(state, source, 0);
            case SELF_PLAYER:
            case RIVAL_PLAYER:
            default:
                return Collections.emptyList();
        }
    }

    private List<CardInstance> rivalUnitsOnField(GameState state, CardInstance source) {
        Player rival = state.getOpponent(source.getOwnerId());
        List<CardInstance> units = new ArrayList<CardInstance>();
        for (CardInstance card : rival.getField()) {
            if (card.isUnit()) {
                units.add(card);
            }
        }
        return units;
    }

    // ------------------------------------------------------------------
    // Handlers (un par EffectType)
    // ------------------------------------------------------------------

/**
 * Gère l'effet DAMAGE : apply damage to target(s), reduces effective power
 * (base + bonus - damage). If lethal (damage >= base + bonus), defeatUnit.
 */
    private class DamageHandler implements EffectHandler {
        /**
         * Applique des dégâts à la cible. La puissance effective est réduite
         * automatiquement via {@link CardInstance#getEffectivePower()}.
         */
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            List<CardInstance> targets = resolveUnitTargets(state, source, effect, target);
            if (targets.isEmpty()) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "DAMAGE " + effect.getValue() + " sans cible valide");
                return;
            }
            for (CardInstance victim : targets) {
                if (victim.getBasePower() == null) {
                    state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                            "DAMAGE sans effet (cible sans puissance)");
                    continue;
                }
                victim.setDamage(victim.getDamage() + effect.getValue());
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "DAMAGE " + effect.getValue() + " (puissance restante "
                                + victim.getEffectivePowerOrZero() + ")");
                if (victim.isLethalDamage()) {
                    defeatUnit(state, victim);
                    if (state.isGameOver()) {
                        return;
                    }
                }
            }
        }
    }

    /** Gère l'effet HEAL : restaure la puissance effective. */
    private class HealHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            List<CardInstance> targets = resolveUnitTargets(state, source, effect, target);
            if (targets.isEmpty()) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "HEAL " + effect.getValue() + " sans cible valide");
                return;
            }
            for (CardInstance patient : targets) {
                int before = patient.getDamage();
                patient.setDamage(before - effect.getValue());
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "HEAL " + (before - patient.getDamage()) + " dégâts soignés");
            }
        }
    }

    /** Gère l'effet DRAW : pioche, vérifie défaite si deck vide (fail fast). */
    private class DrawHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            String beneficiary = source.getOwnerId();
            if (effect.getTarget() == EffectTarget.RIVAL_PLAYER) {
                beneficiary = state.getOpponent(source.getOwnerId()).getId();
            }
            boolean wasOver = state.isGameOver();
            int drawn = state.drawCards(beneficiary, effect.getValue());
            state.appendEvent(GameEventType.CARD_DRAWN, beneficiary,
                    "pioche " + drawn + " carte(s) par effet");
            if (!wasOver && state.isGameOver()) {
                state.appendEvent(GameEventType.GAME_WON, state.getWinnerId(),
                        state.getEndReason());
            }
        }
    }

    /** Gère l'effet GRANT_POWER : augmente powerBonus (puissance effective). */
    private class GrantPowerHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            List<CardInstance> targets = resolveUnitTargets(state, source, effect, target);
            if (targets.isEmpty()) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "GRANT_POWER " + effect.getValue() + " sans cible valide");
                return;
            }
            for (CardInstance buffed : targets) {
                if (buffed.getBasePower() == null) {
                    state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                            "GRANT_POWER sans effet (cible sans puissance)");
                    continue;
                }
                buffed.setPowerBonus(buffed.getPowerBonus() + effect.getValue());
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "GRANT_POWER +" + effect.getValue() + " (puissance "
                                + buffed.getEffectivePowerOrZero() + ")");
            }
        }
    }

    /** Gère l'effet STEAL_GIG : vole un Gig adverse (max 1 par effet selon valeur). */
    private class StealGigHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            String thief = source.getOwnerId();
            String victim = state.getOpponent(thief).getId();
            if (effect.getTarget() == EffectTarget.SELF_PLAYER) {
                String swap = thief;
                thief = victim;
                victim = swap;
            }
            int stolen = 0;
            for (int i = 0; i < effect.getValue(); i++) {
                Optional<Integer> gig = state.stealGig(victim, thief);
                if (!gig.isPresent()) {
                    break;
                }
                stolen++;
            }
            if (stolen == 0) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "STEAL_GIG sans effet (aucun Gig à voler)");
            } else {
                state.appendEvent(GameEventType.GIG_STOLEN, thief,
                        "vol de " + stolen + " Gig(s) par effet (total "
                                + state.getPlayer(thief).getGigCount() + ")");
            }
        }
    }

    /** Gère l'effet REDUCE_COST : augmente costDiscount (remise au début du tour réinitialisée). */
    private class ReduceCostHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            String beneficiary = source.getOwnerId();
            if (effect.getTarget() == EffectTarget.RIVAL_PLAYER) {
                beneficiary = state.getOpponent(source.getOwnerId()).getId();
            }
            Player player = state.getPlayer(beneficiary);
            player.setCostDiscount(player.getCostDiscount() + effect.getValue());
            state.appendEvent(GameEventType.EFFECT_RESOLVED, beneficiary,
                    "REDUCE_COST +" + effect.getValue() + " (remise active "
                            + player.getCostDiscount() + ")");
        }
    }

    /** Unit alliée la plus puissante du Field (choix déterministe). */
    private List<CardInstance> strongestFriendlyUnit(GameState state, CardInstance source) {
        Player owner = state.getPlayer(source.getOwnerId());
        List<CardInstance> units = new ArrayList<CardInstance>();
        for (CardInstance card : owner.getField()) {
            if (card.isUnit()) {
                units.add(card);
            }
        }
        return best(units);
    }

    /**
     * Unit rivale la plus puissante du Field, éventuellement plafonnée en
     * puissance ({@code maxPower = 0} → aucun plafond).
     */
    private List<CardInstance> strongestRivalUnit(GameState state, CardInstance source, int maxPower) {
        List<CardInstance> units = new ArrayList<CardInstance>();
        for (CardInstance card : rivalUnitsOnField(state, source)) {
            if (maxPower <= 0 || card.getEffectivePowerOrZero() <= maxPower) {
                units.add(card);
            }
        }
        return best(units);
    }

    /** Garde uniquement l'exemplaire de plus grande puissance effective (premier en cas d'égalité). */
    private List<CardInstance> best(List<CardInstance> candidates) {
        CardInstance best = null;
        for (CardInstance candidate : candidates) {
            if (best == null || candidate.getEffectivePowerOrZero() > best.getEffectivePowerOrZero()) {
                best = candidate;
            }
        }
        return best == null ? Collections.<CardInstance>emptyList() : Collections.singletonList(best);
    }

    /** Détails null-safe pour le journal de diagnostic. */
    private static java.util.Map<String, Object> detail(Object... keyValues) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    /**
     * Gère l'effet {@code DEFEAT_UNIT} : vainc une Unit (la plus puissante
     * éligible, ou la cible désignée). La valeur de l'effet est un plafond de
     * puissance ({@code 0} = aucun).
     */
    private class DefeatUnitHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            List<CardInstance> victims;
            if (effect.getTarget() == EffectTarget.TARGET_UNIT) {
                victims = resolveUnitTargets(state, source, effect, target);
            } else {
                victims = strongestRivalUnit(state, source, effect.getValue());
            }
            if (victims.isEmpty()) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "DEFEAT_UNIT sans cible valide (plafond de puissance " + effect.getValue() + ")");
                return;
            }
            for (CardInstance victim : victims) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "DEFEAT_UNIT : " + victim.getName() + " vaincue");
                defeatUnit(state, victim);
                if (state.isGameOver()) {
                    return;
                }
            }
        }
    }

    /** Gère l'effet {@code BOOST_GIG} : augmente un Gig du bénéficiaire (« by up to N » = N). */
    private class BoostGigHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            Player beneficiary = state.getPlayer(source.getOwnerId());
            if (effect.getTarget() == EffectTarget.RIVAL_PLAYER) {
                beneficiary = state.getOpponent(source.getOwnerId());
            }
            Optional<Integer> upgraded = boostGig(beneficiary, effect.getValue());
            if (!upgraded.isPresent()) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "BOOST_GIG sans effet (aucun Gig à augmenter)");
                return;
            }
            state.appendEvent(GameEventType.EFFECT_RESOLVED, beneficiary.getId(),
                    "Gig augmenté de " + effect.getValue() + " (nouvelle valeur " + upgraded.get()
                            + ", Street Cred " + beneficiary.getStreetCred() + ")");
        }
    }

    /** Gère l'effet {@code REDUCE_GIG} : diminue un Gig du bénéficiaire (jamais sous 1). */
    private class ReduceGigHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            Player victim = state.getOpponent(source.getOwnerId());
            if (effect.getTarget() == EffectTarget.SELF_PLAYER) {
                victim = state.getPlayer(source.getOwnerId());
            }
            Optional<Integer> lowered = reduceGig(victim, effect.getValue());
            if (!lowered.isPresent()) {
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "REDUCE_GIG sans effet (aucun Gig à diminuer)");
                return;
            }
            state.appendEvent(GameEventType.EFFECT_RESOLVED, victim.getId(),
                    "Gig diminué (nouvelle valeur " + lowered.get()
                            + ", Street Cred " + victim.getStreetCred() + ")");
        }
    }

    /** Gère l'effet DISCARD : trash N cartes du dessus du deck (Glossary TRASH). */
    private class DiscardHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            String ownerId = source.getOwnerId();
            if (effect.getTarget() == EffectTarget.RIVAL_PLAYER) {
                ownerId = state.getOpponent(source.getOwnerId()).getId();
            }
            Player player = state.getPlayer(ownerId);
            int count = effect.getValue();
            int trashed = 0;
            for (int i = 0; i < count; i++) {
                if (player.getDeck().isEmpty()) break;
                CardInstance top = player.getDeck().remove(0);
                top.setZone(Zone.TRASH);
                player.getTrash().add(top);
                trashed++;
            }
            state.appendEvent(GameEventType.EFFECT_RESOLVED, ownerId,
                    "DISCARD " + trashed + "/" + count + " cartes trashées");
        }
    }

    /** Gère l'effet BUFF : alias de GRANT_POWER (+N power sur SELF). */
    private class BuffHandler implements EffectHandler {
        @Override
        public void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target) {
            List<CardInstance> targets = resolveUnitTargets(state, source, effect, target);
            if (targets.isEmpty()) {
                // Fallback : buff self if no target resolved
                targets = Collections.singletonList(source);
            }
            for (CardInstance buffed : targets) {
                if (buffed.getBasePower() == null) {
                    state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                            "BUFF sans effet (cible sans puissance)");
                    continue;
                }
                buffed.setPowerBonus(buffed.getPowerBonus() + effect.getValue());
                state.appendEvent(GameEventType.EFFECT_RESOLVED, source.getOwnerId(),
                        "BUFF +" + effect.getValue() + " (puissance " + buffed.getEffectivePowerOrZero() + ")");
            }
        }
    }

    /**
     * Augmente le Gig de plus faible valeur (« by up to N » : la valeur maximale
     * est appliquée, choix déterministe).
     *
     * @return la nouvelle valeur, ou vide si le joueur ne contrôle aucun Gig
     */
    Optional<Integer> boostGig(Player player, int amount) {
        if (player.getGigs().isEmpty() || amount <= 0) {
            return Optional.empty();
        }
        int index = lowestGigIndex(player);
        int updated = player.getGigs().get(index) + amount;
        player.getGigs().set(index, updated);
        return Optional.of(updated);
    }

    /**
     * Diminue le Gig de plus forte valeur sans descendre sous 1.
     *
     * @return la nouvelle valeur, ou vide si le joueur ne contrôle aucun Gig
     */
    Optional<Integer> reduceGig(Player player, int amount) {
        if (player.getGigs().isEmpty() || amount <= 0) {
            return Optional.empty();
        }
        int index = highestGigIndex(player);
        int updated = Math.max(1, player.getGigs().get(index) - amount);
        player.getGigs().set(index, updated);
        return Optional.of(updated);
    }

    private int lowestGigIndex(Player player) {
        int index = 0;
        for (int i = 1; i < player.getGigs().size(); i++) {
            if (player.getGigs().get(i) < player.getGigs().get(index)) {
                index = i;
            }
        }
        return index;
    }

    private int highestGigIndex(Player player) {
        int index = 0;
        for (int i = 1; i < player.getGigs().size(); i++) {
            if (player.getGigs().get(i) > player.getGigs().get(index)) {
                index = i;
            }
        }
        return index;
    }
}
