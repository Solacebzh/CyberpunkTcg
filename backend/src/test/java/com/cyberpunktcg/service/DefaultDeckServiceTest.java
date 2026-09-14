package com.cyberpunktcg.service;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardRarity;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.lobby.LobbyException;
import com.cyberpunktcg.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests du service de deck par défaut : génération 3 legends + 10 units et
 * validation des decks personnalisés.
 */
@ExtendWith(MockitoExtension.class)
class DefaultDeckServiceTest {

    @Mock
    private CardRepository cardRepository;

    private DefaultDeckService deckService;

    @BeforeEach
    void setUp() {
        deckService = new DefaultDeckService(cardRepository);
        org.mockito.Mockito.lenient().when(cardRepository.findByTypeOrderByNameAscIdAsc(CardType.LEGEND))
                .thenReturn(cards(CardType.LEGEND, "legend", 5));
        org.mockito.Mockito.lenient().when(cardRepository.findByTypeOrderByNameAscIdAsc(CardType.UNIT))
                .thenReturn(cards(CardType.UNIT, "unit", 12));
    }

    @Test
    void defaultDeck_troisLegendsDixUnits() {
        List<String> deck = deckService.defaultDeck();

        assertThat(deck).hasSize(13);
        when(cardRepository.findAllById(any())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(this::cardFromId).toList());
        List<Card> resolved = cardRepository.findAllById(deck);
        assertThat(resolved.stream().filter(card -> card.getType() == CardType.LEGEND)).hasSize(3);
        assertThat(resolved.stream().filter(card -> card.getType() == CardType.UNIT)).hasSize(10);
    }

    @Test
    void resolveDeck_vide_renvoieLeDeckParDefaut() {
        assertThat(deckService.resolveDeck(null)).hasSize(13);
        assertThat(deckService.resolveDeck(List.of())).hasSize(13);
    }

    @Test
    void resolveDeck_carteInconnue_rejetee() {
        List<String> requested = validDeck();
        when(cardRepository.findAllById(any())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream()
                        .filter(id -> !id.equals("ghost"))
                        .map(this::cardFromId).toList());
        requested = new ArrayList<>(requested);
        requested.set(3, "ghost");

        List<String> bad = requested;
        assertThatThrownBy(() -> deckService.resolveDeck(bad))
                .isInstanceOf(LobbyException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void resolveDeck_mauvaisNombreDeLegends_rejete() {
        List<String> requested = new ArrayList<>(validDeck());
        requested.set(0, "unit-extra");
        when(cardRepository.findAllById(any())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(this::cardFromId).toList());

        assertThatThrownBy(() -> deckService.resolveDeck(requested))
                .isInstanceOf(LobbyException.class)
                .hasMessageContaining("3 Legends");
    }

    @Test
    void resolveDeck_doublons_rejetes() {
        List<String> requested = new ArrayList<>(validDeck());
        requested.set(3, requested.get(4));

        assertThatThrownBy(() -> deckService.resolveDeck(requested))
                .isInstanceOf(LobbyException.class)
                .hasMessageContaining("double");
    }

    private List<String> validDeck() {
        List<String> ids = new ArrayList<>();
        ids.add("legend-0");
        ids.add("legend-1");
        ids.add("legend-2");
        for (int i = 0; i < 10; i++) {
            ids.add("unit-" + i);
        }
        return ids;
    }

    private Card cardFromId(String id) {
        CardType type = id.startsWith("legend") ? CardType.LEGEND
                : id.startsWith("gear") ? CardType.GEAR : CardType.UNIT;
        Integer power = type == CardType.LEGEND ? null : 2;
        return new Card(id, "Test " + id, null, type, CardColor.RED, 1,
                1, power, null, new ArrayList<String>(),
                new ArrayList<CardKeyword>(), "test", new ArrayList<String>(),
                null, "TEST", "001", CardRarity.COMMON);
    }

    private List<Card> cards(CardType type, String prefix, int count) {
        List<Card> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(cardFromId(prefix + "-" + i));
        }
        return result;
    }
}
