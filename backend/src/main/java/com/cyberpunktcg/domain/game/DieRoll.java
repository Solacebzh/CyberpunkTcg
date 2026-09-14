package com.cyberpunktcg.domain.game;

/**
 * Résultat du lancer d'un dé Gig (dé conservé + valeur obtenue).
 */
public class DieRoll {

    private final String die;
    private final int value;

    public DieRoll(String die, int value) {
        if (die == null) {
            throw new IllegalArgumentException("Le dé est obligatoire");
        }
        this.die = die;
        this.value = value;
    }

    /** Type de dé lancé ({@code d4}, {@code d6}, {@code d8}, {@code d10}, {@code d12}, {@code d20}). */
    public String getDie() {
        return die;
    }

    /** Valeur obtenue (entre 1 et le nombre de faces). */
    public int getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "DieRoll{die='" + die + "', value=" + value + '}';
    }
}
