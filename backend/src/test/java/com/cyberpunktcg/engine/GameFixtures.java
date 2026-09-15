package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardRarity;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fabriques de test pour le moteur : définitions de cartes synthétiques
 * (mini-langage d'effets) et duels prêts à jouer.
 */
public final class GameFixtures {

    private GameFixtures() {
        // Classe utilitaire non instanciable.
    }

    /** Définition de carte synthétique (texte = capacités jointes). */
    public static Card card(String id, CardType type, Integer cost, Integer power,
                            List<CardKeyword> keywords, List<String> abilities) {
        return coloredCard(id, type, CardColor.RED, 1, cost, power, keywords, abilities);
    }

    /**
     * Définition de carte synthétique avec couleur et RAM explicites.
     *
     * <p>La RAM n'est pas une ressource consommée : c'est la valeur imprimée qui
     * doit rester sous le plafond de la couleur (somme des RAM des Legends).</p>
     */
    public static Card coloredCard(String id, CardType type, CardColor color, int ram,
                                   Integer cost, Integer power, List<CardKeyword> keywords,
                                   List<String> abilities) {
        String text = abilities.isEmpty() ? "test card" : String.join(" ", abilities);
        List<String> tags = new ArrayList<String>();
        return new Card(id, "Test " + id, null, type, color, ram,
                cost, power, null, tags, new ArrayList<CardKeyword>(keywords), text,
                new ArrayList<String>(abilities), null, "TEST", "001", CardRarity.COMMON);
    }

    /** Unit colorée (RAM explicite). */
    public static Card coloredUnit(String id, CardColor color, int ram, int cost, int power,
                                   CardKeyword... keywords) {
        return coloredCard(id, CardType.UNIT, color, ram, cost, power,
                Arrays.asList(keywords), Collections.<String>emptyList());
    }

    /** Program coloré (RAM explicite). */
    public static Card coloredProgram(String id, CardColor color, int ram, int cost, String ability,
                                      CardKeyword... keywords) {
        List<String> abilities = new ArrayList<String>();
        if (ability != null) {
            abilities.add(ability);
        }
        return coloredCard(id, CardType.PROGRAM, color, ram, cost, null,
                Arrays.asList(keywords), abilities);
    }

    /** Legend colorée de RAM explicite (aucun coût en Eddies). */
    public static Card coloredLegend(String id, CardColor color, int ram, String ability,
                                     CardKeyword... keywords) {
        List<String> abilities = new ArrayList<String>();
        if (ability != null) {
            abilities.add(ability);
        }
        return coloredCard(id, CardType.LEGEND, color, ram, null, 2,
                Arrays.asList(keywords), abilities);
    }

    public static Card unit(String id, int cost, int power, CardKeyword... keywords) {
        return card(id, CardType.UNIT, cost, power,
                Arrays.asList(keywords), Collections.<String>emptyList());
    }

    public static Card unit(String id, int cost, int power, String ability, CardKeyword... keywords) {
        List<String> abilities = new ArrayList<String>();
        abilities.add(ability);
        return card(id, CardType.UNIT, cost, power, Arrays.asList(keywords), abilities);
    }

    public static Card unitWithCred(String id, int cost, int power, int streetCred, CardKeyword... keywords) {
        Card base = unit(id, cost, power, keywords);
        return new Card(base.getId(), base.getName(), null, base.getType(), base.getColor(), 1,
                base.getCost(), base.getPower(), streetCred, new ArrayList<String>(),
                new ArrayList<CardKeyword>(base.getKeywords()), base.getText(),
                new ArrayList<String>(base.getAbilities()), null, "TEST", "001", CardRarity.COMMON);
    }

    public static Card program(String id, int cost, CardKeyword... keywords) {
        return card(id, CardType.PROGRAM, cost, null,
                Arrays.asList(keywords), Collections.<String>emptyList());
    }

    public static Card program(String id, int cost, String ability, CardKeyword... keywords) {
        List<String> abilities = new ArrayList<String>();
        abilities.add(ability);
        return card(id, CardType.PROGRAM, cost, null, Arrays.asList(keywords), abilities);
    }

    public static Card gear(String id, int cost, int power, CardKeyword... keywords) {
        return card(id, CardType.GEAR, cost, power,
                Arrays.asList(keywords), Collections.<String>emptyList());
    }

    public static Card legend(String id, String ability, CardKeyword... keywords) {
        List<String> abilities = new ArrayList<String>();
        if (ability != null) {
            abilities.add(ability);
        }
        return card(id, CardType.LEGEND, null, 2, Arrays.asList(keywords), abilities);
    }

    /**
     * Duel frais : p1 actif en MAIN (tour 1), decks de 10 figurants 1/1,
     * mains vides, ressources à zéro.
     */
    public static GameState freshDuel() {
        Player p1 = new Player("p1", "p1");
        Player p2 = new Player("p2", "p2");
        for (int i = 0; i < 10; i++) {
            p1.getDeck().add(CardInstance.fromCard(unit("filler-p1-" + i, 1, 1), "p1", Zone.DECK));
            p2.getDeck().add(CardInstance.fromCard(unit("filler-p2-" + i, 1, 1), "p2", Zone.DECK));
        }
        List<Player> players = new ArrayList<Player>();
        players.add(p1);
        players.add(p2);
        return new GameState("game-test", players, 42L);
    }

    /** Ajoute une carte en main et retourne son exemplaire. */
    public static CardInstance handCard(GameState state, String playerId, Card card) {
        CardInstance instance = CardInstance.fromCard(card, playerId, Zone.HAND);
        state.getPlayer(playerId).getHand().add(instance);
        return instance;
    }

    /** Pose une carte prête sur le Field (ni épuisée, ni mal d'invocation). */
    public static CardInstance fieldCard(GameState state, String playerId, Card card) {
        CardInstance instance = CardInstance.fromCard(card, playerId, Zone.FIELD);
        instance.setExhausted(false);
        instance.setSummoningSickness(false);
        state.getPlayer(playerId).getField().add(instance);
        return instance;
    }

    /** Ajoute une Legend (face cachée ou non) dans la Legends Area. */
    public static CardInstance legendCard(GameState state, String playerId, Card card, boolean faceDown) {
        CardInstance instance = CardInstance.fromCard(card, playerId, Zone.LEGENDS_AREA);
        instance.setFaceDown(faceDown);
        state.getPlayer(playerId).getLegendsArea().add(instance);
        return instance;
    }

    public static void giveEddies(GameState state, String playerId, int amount) {
        state.getPlayer(playerId).setEddies(state.getPlayer(playerId).getEddies() + amount);
    }

    public static void addGigs(GameState state, String playerId, int... values) {
        for (int value : values) {
            state.getPlayer(playerId).getGigs().add(value);
        }
    }
}
