package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.CardColor;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Résultat de la validation d'un deck selon les règles officielles du jeu (Mini-Feature 8).
 */
public class DeckValidationResult {

    private final boolean valid;
    private final List<String> errors;
    private final Map<CardColor, Integer> ramCeilings;
    private final int legendsCount;
    private final int mainDeckCount;

    public DeckValidationResult(
            boolean valid,
            List<String> errors,
            Map<CardColor, Integer> ramCeilings,
            int legendsCount,
            int mainDeckCount
    ) {
        this.valid = valid;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
        this.ramCeilings = ramCeilings != null ? Map.copyOf(ramCeilings) : Map.of();
        this.legendsCount = legendsCount;
        this.mainDeckCount = mainDeckCount;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public Map<CardColor, Integer> getRamCeilings() {
        return ramCeilings;
    }

    public int getRamCeiling(CardColor color) {
        if (color == null) {
            return 0;
        }
        return ramCeilings.getOrDefault(color, 0);
    }

    public int getLegendsCount() {
        return legendsCount;
    }

    public int getMainDeckCount() {
        return mainDeckCount;
    }

    public String getErrorMessage() {
        return String.join("; ", errors);
    }

    public static DeckValidationResult success(
            Map<CardColor, Integer> ramCeilings,
            int legendsCount,
            int mainDeckCount
    ) {
        return new DeckValidationResult(true, List.of(), ramCeilings, legendsCount, mainDeckCount);
    }

    public static DeckValidationResult failure(
            List<String> errors,
            Map<CardColor, Integer> ramCeilings,
            int legendsCount,
            int mainDeckCount
    ) {
        return new DeckValidationResult(false, errors, ramCeilings, legendsCount, mainDeckCount);
    }

    @Override
    public String toString() {
        return "DeckValidationResult{" +
                "valid=" + valid +
                ", errors=" + errors +
                ", ramCeilings=" + ramCeilings +
                ", legendsCount=" + legendsCount +
                ", mainDeckCount=" + mainDeckCount +
                '}';
    }
}
