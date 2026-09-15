package com.cyberpunktcg.domain.card;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum CardColor {
    RED("red", "rouge"),
    GREEN("green", "vert"),
    BLUE("blue", "bleu"),
    YELLOW("yellow", "jaune");

    private final String value;
    private final String label;

    CardColor(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /** Valeur JSON (anglais, stable pour l'API). */
    @JsonValue
    public String value() {
        return value;
    }

    /** Libellé français, pour les messages d'erreur et le journal de partie. */
    public String label() {
        return label;
    }

    @JsonCreator
    public static CardColor fromValue(String value) {
        return Arrays.stream(values())
                .filter(color -> color.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown card color: " + value));
    }
}
