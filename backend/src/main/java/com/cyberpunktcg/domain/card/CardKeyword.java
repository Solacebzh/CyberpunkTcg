package com.cyberpunktcg.domain.card;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum CardKeyword {
    GO_SOLO("go_solo"),
    ADRENALINE("adrenaline"),
    BLOCKER("blocker"),
    QUICK("quick"),
    FLIP("flip"),
    PLAY("play"),
    ATTACK("attack"),
    CALL("call"),
    DEFEATED("defeated"),
    SPEND("spend");

    private final String value;

    CardKeyword(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CardKeyword fromValue(String value) {
        return Arrays.stream(values())
                .filter(keyword -> keyword.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown card keyword: " + value));
    }
}
