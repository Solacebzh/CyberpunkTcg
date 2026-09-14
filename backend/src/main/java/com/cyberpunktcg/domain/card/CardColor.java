package com.cyberpunktcg.domain.card;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum CardColor {
    RED("red"), GREEN("green"), BLUE("blue"), YELLOW("yellow");

    private final String value;

    CardColor(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CardColor fromValue(String value) {
        return Arrays.stream(values())
                .filter(color -> color.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown card color: " + value));
    }
}
