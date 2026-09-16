package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du validateur de deck officiel (Mini-Feature 8).
 *
 * <p>Vérifie les 4 règles officielles :</p>
 * <ol>
 *   <li>Exactement 3 Legends uniques.</li>
 *   <li>Main Deck entre 40 et 50 cartes (Units, Programs, Gears).</li>
 *   <li>Maximum 3 exemplaires de la même carte dans le Main Deck.</li>
 *   <li>Plafond de RAM par couleur calculé depuis les Legends.</li>
 * </ol>
 */
class DeckValidatorTest {

    private Card redLegend1;
    private Card redLegend2;
    private Card greenLegend;

    private List<Card> validLegends;
    private List<Card> validMainDeck;

    @BeforeEach
    void setUp() {
        // 2 Legends rouges (2 RAM chacune) + 1 Legend verte (1 RAM)
        // -> Plafond Rouge: 4, Vert: 1, Bleu: 0, Jaune: 0
        redLegend1 = GameFixtures.coloredLegend("leg-red-1", CardColor.RED, 2, null);
        redLegend2 = GameFixtures.coloredLegend("leg-red-2", CardColor.RED, 2, null);
        greenLegend = GameFixtures.coloredLegend("leg-green-1", CardColor.GREEN, 1, null);

        validLegends = List.of(redLegend1, redLegend2, greenLegend);

        // Main Deck de 40 cartes valides :
        // 10 cartes rouges x 3 copies = 30 cartes (RAM de 1 à 4 <= plafond 4)
        // 3 cartes vertes x 3 copies = 9 cartes (RAM 1 <= plafond 1)
        // 1 carte verte x 1 copie = 1 carte (RAM 1 <= plafond 1)
        // Total = 40 cartes, max 3 exemplaires par carte
        validMainDeck = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int ram = (i % 4) + 1; // RAM entre 1 et 4
            Card redCard = GameFixtures.coloredUnit("unit-red-" + i, CardColor.RED, ram, 2, 3);
            for (int c = 0; c < 3; c++) {
                validMainDeck.add(redCard);
            }
        }
        for (int i = 0; i < 3; i++) {
            Card greenCard = GameFixtures.coloredUnit("unit-green-" + i, CardColor.GREEN, 1, 2, 2);
            for (int c = 0; c < 3; c++) {
                validMainDeck.add(greenCard);
            }
        }
        validMainDeck.add(GameFixtures.coloredUnit("unit-green-single", CardColor.GREEN, 1, 1, 1));
    }

    @Test
    @DisplayName("Un deck respectant strictement les 4 conditions est valide")
    void testDeckValidation_Valid() {
        // Validation via méthode (legends, mainDeck)
        DeckValidationResult result = DeckValidator.validate(validLegends, validMainDeck);

        assertThat(result.isValid()).isTrue();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getLegendsCount()).isEqualTo(3);
        assertThat(result.getMainDeckCount()).isEqualTo(40);
        assertThat(result.getRamCeiling(CardColor.RED)).isEqualTo(4);
        assertThat(result.getRamCeiling(CardColor.GREEN)).isEqualTo(1);
        assertThat(result.getRamCeiling(CardColor.BLUE)).isZero();
        assertThat(result.getRamCeiling(CardColor.YELLOW)).isZero();

        // Validation via deck combiné unique
        List<Card> fullDeck = new ArrayList<>(validLegends);
        fullDeck.addAll(validMainDeck);
        DeckValidationResult fullResult = DeckValidator.validate(fullDeck);

        assertThat(fullResult.isValid()).isTrue();
        assertThat(fullResult.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("Un deck avec un nombre incorrect de Legends ou des Legends en double est invalide")
    void testDeckValidation_InvalidLegendsCount() {
        // Trop peu de Legends (2 au lieu de 3)
        List<Card> twoLegends = List.of(redLegend1, redLegend2);
        DeckValidationResult resUnder = DeckValidator.validate(twoLegends, validMainDeck);
        assertThat(resUnder.isValid()).isFalse();
        assertThat(resUnder.getErrors()).anyMatch(err -> err.contains("3 Legends"));

        // Trop de Legends (4 au lieu de 3)
        Card extraLegend = GameFixtures.coloredLegend("leg-extra", CardColor.RED, 1, null);
        List<Card> fourLegends = List.of(redLegend1, redLegend2, greenLegend, extraLegend);
        DeckValidationResult resOver = DeckValidator.validate(fourLegends, validMainDeck);
        assertThat(resOver.isValid()).isFalse();
        assertThat(resOver.getErrors()).anyMatch(err -> err.contains("3 Legends"));

        // Exactement 3 Legends, mais avec doublon d'identifiant
        List<Card> duplicateLegends = List.of(redLegend1, redLegend1, greenLegend);
        DeckValidationResult resDup = DeckValidator.validate(duplicateLegends, validMainDeck);
        assertThat(resDup.isValid()).isFalse();
        assertThat(resDup.getErrors()).anyMatch(err -> err.contains("double"));

        // Aucune Legend
        DeckValidationResult resZero = DeckValidator.validate(List.of(), validMainDeck);
        assertThat(resZero.isValid()).isFalse();
        assertThat(resZero.getErrors()).anyMatch(err -> err.contains("3 Legends"));
    }

    @Test
    @DisplayName("Un Main Deck contenant plus de 3 copies de la même carte est invalide")
    void testDeckValidation_TooManyCopies() {
        List<Card> deckWith4Copies = new ArrayList<>(validMainDeck);
        // On retire la carte unique et on ajoute un 4e exemplaire de "unit-red-0"
        deckWith4Copies.remove(deckWith4Copies.size() - 1);
        Card unitRed0 = GameFixtures.coloredUnit("unit-red-0", CardColor.RED, 1, 2, 3);
        deckWith4Copies.add(unitRed0);

        assertThat(deckWith4Copies).hasSize(40);

        DeckValidationResult result = DeckValidator.validate(validLegends, deckWith4Copies);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(err ->
                err.contains("3 exemplaires") && err.contains("unit-red-0")
        );
    }

    @Test
    @DisplayName("Un Main Deck hors des bornes 40 à 50 cartes est invalide")
    void testDeckValidation_MainDeckSizeOutOfBounds() {
        // 39 cartes (< 40)
        List<Card> deck39 = new ArrayList<>(validMainDeck);
        deck39.remove(deck39.size() - 1);
        assertThat(deck39).hasSize(39);

        DeckValidationResult res39 = DeckValidator.validate(validLegends, deck39);
        assertThat(res39.isValid()).isFalse();
        assertThat(res39.getErrors()).anyMatch(err -> err.contains("entre 40 et 50 cartes"));

        // 51 cartes (> 50) : on part des 40 cartes valides et on ajoute 11 cartes autorisées
        List<Card> deck51 = new ArrayList<>(validMainDeck);
        for (int i = 0; i < 11; i++) {
            deck51.add(GameFixtures.coloredUnit("extra-red-" + i, CardColor.RED, 1, 1, 1));
        }
        assertThat(deck51).hasSize(51);

        DeckValidationResult res51 = DeckValidator.validate(validLegends, deck51);
        assertThat(res51.isValid()).isFalse();
        assertThat(res51.getErrors()).anyMatch(err -> err.contains("entre 40 et 50 cartes"));
    }

    @Test
    @DisplayName("Un Main Deck dépassant le plafond de RAM ou contenant une couleur interdite est invalide")
    void testDeckValidation_RamCeilingExceeded() {
        // Cas 1 : carte rouge avec un coût en RAM de 5 (dépasse le plafond rouge de 4)
        List<Card> deckRamExceeded = new ArrayList<>(validMainDeck);
        deckRamExceeded.remove(deckRamExceeded.size() - 1);
        Card expensiveRedCard = GameFixtures.coloredUnit("unit-red-expensive", CardColor.RED, 5, 5, 5);
        deckRamExceeded.add(expensiveRedCard);

        DeckValidationResult resRam = DeckValidator.validate(validLegends, deckRamExceeded);
        assertThat(resRam.isValid()).isFalse();
        assertThat(resRam.getErrors()).anyMatch(err ->
                err.contains("dépasse le plafond") && err.contains("5 RAM")
        );

        // Cas 2 : carte bleue (plafond bleu = 0 car aucune Legend bleue)
        List<Card> deckForbiddenColor = new ArrayList<>(validMainDeck);
        deckForbiddenColor.remove(deckForbiddenColor.size() - 1);
        Card blueCard = GameFixtures.coloredUnit("unit-blue", CardColor.BLUE, 1, 2, 2);
        deckForbiddenColor.add(blueCard);

        DeckValidationResult resColor = DeckValidator.validate(validLegends, deckForbiddenColor);
        assertThat(resColor.isValid()).isFalse();
        assertThat(resColor.getErrors()).anyMatch(err ->
                err.contains("plafond de RAM est 0") || err.contains("bleu")
        );
    }

    @Test
    @DisplayName("Un Main Deck contenant une carte de type Legend est rejeté")
    void testDeckValidation_MainDeckContainsLegend() {
        List<Card> deckWithLegend = new ArrayList<>(validMainDeck);
        deckWithLegend.remove(deckWithLegend.size() - 1);
        Card rogueLegend = GameFixtures.coloredLegend("rogue-legend", CardColor.RED, 1, null);
        deckWithLegend.add(rogueLegend);

        DeckValidationResult result = DeckValidator.validate(validLegends, deckWithLegend);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(err -> err.contains("Main Deck ne peut pas contenir de carte Legend"));
    }

    @Test
    @DisplayName("Calcul correct des plafonds de RAM selon les Legends")
    void testCalculateRamCeilings() {
        Card red2 = GameFixtures.coloredLegend("r2", CardColor.RED, 2, null);
        Card blue2 = GameFixtures.coloredLegend("b2", CardColor.BLUE, 2, null);
        Card yellow1 = GameFixtures.coloredLegend("y1", CardColor.YELLOW, 1, null);

        Map<CardColor, Integer> ceilings = DeckValidator.calculateRamCeilings(List.of(red2, blue2, yellow1));
        assertThat(ceilings.get(CardColor.RED)).isEqualTo(2);
        assertThat(ceilings.get(CardColor.BLUE)).isEqualTo(2);
        assertThat(ceilings.get(CardColor.YELLOW)).isEqualTo(1);
        assertThat(ceilings.get(CardColor.GREEN)).isZero();
    }
}
