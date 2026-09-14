package com.cyberpunktcg.engine;

/**
 * Effet structuré issu de l'interprétation d'une capacité de carte
 * (voir {@link EffectParser} et {@code docs/RULE-ENGINE.md}).
 */
public class GameEffect {

    private final TriggerType trigger;
    private final EffectType type;
    private final int value;
    private final EffectTarget target;

    public GameEffect(TriggerType trigger, EffectType type, int value, EffectTarget target) {
        if (trigger == null) {
            throw new IllegalArgumentException("Le déclencheur est obligatoire");
        }
        if (type == null) {
            throw new IllegalArgumentException("Le type d'effet est obligatoire");
        }
        if (target == null) {
            throw new IllegalArgumentException("La cible est obligatoire");
        }
        this.trigger = trigger;
        this.type = type;
        this.value = value;
        this.target = target;
    }

    public TriggerType getTrigger() {
        return trigger;
    }

    public EffectType getType() {
        return type;
    }

    public int getValue() {
        return value;
    }

    public EffectTarget getTarget() {
        return target;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GameEffect)) {
            return false;
        }
        GameEffect that = (GameEffect) other;
        return value == that.value
                && trigger == that.trigger
                && type == that.type
                && target == that.target;
    }

    @Override
    public int hashCode() {
        int result = trigger.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + value;
        result = 31 * result + target.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return trigger + ":" + type + ":" + value + ":" + target;
    }
}
