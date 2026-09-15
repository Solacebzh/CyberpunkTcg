package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEventType;
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
            return;
        }
        handler.apply(state, source, effect, target);
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
        resolveEffects(state, actual, TriggerType.ON_DEATH, null);
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
}
