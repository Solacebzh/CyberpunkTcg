package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.CardInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprète les capacités ({@code abilities}) d'une carte depuis leur forme JSON.
 *
 * <p>Deux niveaux en V1 :</p>
 * <ol>
 *   <li><strong>Mini-langage structuré</strong> (prioritaire) :
 *   {@code TRIGGER:EFFET:VALEUR[:CIBLE]}, par exemple
 *   {@code ON_PLAY:DRAW:2}, {@code FLIP:GRANT_POWER:2:SELF},
 *   {@code ON_ATTACK:DAMAGE:3:TARGET_UNIT}. Cible par défaut selon l'effet
 *   (voir {@link #defaultTarget(EffectType)}).</li>
 *   <li><strong>Heuristique de compatibilité</strong> : les textes naturels du
 *   catalogue actuel (ex. « draw 1 ») sont reconnus au cas par cas et convertis
 *   en déclencheur {@code ON_PLAY}. Tout le reste est ignoré (effet non
 *   implémenté en V1, sans erreur).</li>
 * </ol>
 */
public final class EffectParser {

    /** Heuristique V1 : « draw N » (texte naturel) → pioche {@code ON_PLAY}. */
    private static final Pattern DRAW_PATTERN = Pattern.compile("(?i)\\bdraw (\\d+)\\b");

    private EffectParser() {
        // Classe utilitaire non instanciable.
    }

    /**
     * Convertit toutes les capacités d'un exemplaire en effets structurés.
     * Les capacités non reconnues sont ignorées (jamais d'exception).
     */
    public static List<GameEffect> parseAbilities(CardInstance source) {
        List<GameEffect> effects = new ArrayList<GameEffect>();
        for (String ability : source.getAbilities()) {
            GameEffect effect = parseAbility(ability);
            if (effect != null) {
                effects.add(effect);
                continue;
            }
            GameEffect heuristic = parseHeuristic(ability);
            if (heuristic != null) {
                effects.add(heuristic);
            }
        }
        return effects;
    }

    /**
     * Analyse une capacité au format {@code TRIGGER:EFFET:VALEUR[:CIBLE]}.
     *
     * @return l'effet, ou {@code null} si le texte ne suit pas le format
     */
    public static GameEffect parseAbility(String abilityText) {
        if (abilityText == null) {
            return null;
        }
        String[] parts = abilityText.trim().split("\\s*:\\s*");
        if (parts.length < 3 || parts.length > 4) {
            return null;
        }
        TriggerType trigger;
        EffectType type;
        int value;
        try {
            trigger = TriggerType.valueOf(parts[0].trim().toUpperCase());
            type = EffectType.valueOf(parts[1].trim().toUpperCase());
            value = Integer.parseInt(parts[2].trim());
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (value < 0) {
            return null;
        }
        EffectTarget target;
        if (parts.length == 4) {
            try {
                target = EffectTarget.valueOf(parts[3].trim().toUpperCase());
            } catch (IllegalArgumentException error) {
                return null;
            }
        } else {
            target = defaultTarget(type);
        }
        return new GameEffect(trigger, type, value, target);
    }

    /** Cible par défaut d'un effet quand la capacité ne la précise pas. */
    public static EffectTarget defaultTarget(EffectType type) {
        switch (type) {
            case DAMAGE:
                return EffectTarget.TARGET_UNIT;
            case HEAL:
                return EffectTarget.SELF;
            case DRAW:
                return EffectTarget.SELF_PLAYER;
            case GRANT_POWER:
                return EffectTarget.SELF;
            case STEAL_GIG:
                return EffectTarget.RIVAL_PLAYER;
            case REDUCE_COST:
                return EffectTarget.SELF_PLAYER;
            default:
                return EffectTarget.SELF;
        }
    }

    /**
     * Heuristiques de compatibilité pour les textes naturels du catalogue.
     * En V1 : seul « draw N » est reconnu (déclencheur {@code ON_PLAY}).
     *
     * @return l'effet deviné, ou {@code null} si rien n'est reconnu
     */
    static GameEffect parseHeuristic(String abilityText) {
        if (abilityText == null) {
            return null;
        }
        Matcher draw = DRAW_PATTERN.matcher(abilityText);
        if (draw.find()) {
            int value = Integer.parseInt(draw.group(1));
            return new GameEffect(TriggerType.ON_PLAY, EffectType.DRAW, value, EffectTarget.SELF_PLAYER);
        }
        return null;
    }
}
