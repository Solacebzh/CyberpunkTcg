package com.cyberpunktcg.domain.card;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum CardRarity {
    COMMON("common"),
    UNCOMMON("uncommon"),
    RARE("rare"),
    EPIC("epic"),
    SECRET("secret"),
    ICONIC("iconic"),
    NOVA("nova"),
    PROMO("promo");

    private final String value;

    CardRarity(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CardRarity fromValue(String value) {
        return Arrays.stream(values())
                .filter(rarity -> rarity.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown card rarity: " + value));
    }
}
