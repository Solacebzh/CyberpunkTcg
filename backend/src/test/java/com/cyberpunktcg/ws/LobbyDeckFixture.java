package com.cyberpunktcg.ws;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.deck.Deck;
import com.cyberpunktcg.domain.deck.DeckRepository;
import com.cyberpunktcg.domain.user.User;
import com.cyberpunktcg.domain.user.UserRepository;
import com.cyberpunktcg.engine.DeckValidator;
import com.cyberpunktcg.repository.CardRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper d'amorçage de comptes + decks légaux pour les tests d'intégration
 * STOMP du lobby (Mini-Feature 9D).
 *
 * <p>Le salon exige désormais un deck sauvegardé par joueur. Comme les
 * identifiants STOMP ne sont pas authentifiés côté canal (pseudo = display
 * name), on crée un compte portant le même nom pour que
 * {@code DeckService.getDeck(pseudo, deckId)} résolve le deck.</p>
 */
final class LobbyDeckFixture {

    private LobbyDeckFixture() {
    }

    /** Crée un compte dont le username = pseudo STOMP. */
    static User upsertUser(UserRepository userRepository, PasswordEncoder encoder, String username) {
        User created = new User(username, encoder.encode("test-password-" + username));
        return userRepository.save(created);
    }

    /** Construit un deck légal (3 Legends uniques + 40 Main Deck) et le persiste. */
    static Deck persistLegalDeck(DeckRepository deckRepository, CardRepository cardRepository,
                                  String name, Long userId) {
        List<Card> catalog = cardRepository.findAll();
        if (catalog.isEmpty()) {
            throw new IllegalStateException("Le catalogue doit être chargé pour amorcer un deck de test");
        }
        Deck built = buildLegalDeck(catalog, name, userId);
        return deckRepository.save(built);
    }

    private static Deck buildLegalDeck(List<Card> catalog, String name, Long userId) {
        List<Card> legends = new ArrayList<>();
        for (Card card : catalog) {
            if (card.getType() == CardType.LEGEND) {
                legends.add(card);
            }
        }

        CardColor bestColor = null;
        List<Card> bestLegends = new ArrayList<>();
        int bestCeiling = -1;

        for (CardColor color : CardColor.values()) {
            Map<String, Card> uniqueByName = new LinkedHashMap<>();
            for (Card legend : legends) {
                if (legend.getColor() != color || legend.getRam() <= 0) {
                    continue;
                }
                String key = legend.getName().trim().toLowerCase();
                Card already = uniqueByName.get(key);
                if (already == null || legend.getRam() > already.getRam()) {
                    uniqueByName.put(key, legend);
                }
            }
            if (uniqueByName.size() < DeckValidator.REQUIRED_LEGENDS) {
                continue;
            }
            List<Card> candidates = new ArrayList<>(uniqueByName.values());
            candidates.sort((left, right) -> Integer.compare(right.getRam(), left.getRam()));
            List<Card> chosen = new ArrayList<>(candidates.subList(0, DeckValidator.REQUIRED_LEGENDS));

            int ceiling = 0;
            for (Card legend : chosen) {
                ceiling += legend.getRam();
            }
            if (ceiling > bestCeiling) {
                bestCeiling = ceiling;
                bestColor = color;
                bestLegends = chosen;
            }
        }

        if (bestColor == null) {
            throw new IllegalStateException(
                    "Le catalogue doit fournir " + DeckValidator.REQUIRED_LEGENDS + " Legends uniques d'une même couleur");
        }

        List<Card> pool = new ArrayList<>();
        for (Card card : catalog) {
            if (card.getType() == CardType.LEGEND || card.getColor() != bestColor || card.getRam() > bestCeiling) {
                continue;
            }
            pool.add(card);
        }
        pool.sort((left, right) -> left.getId().compareTo(right.getId()));

        int target = DeckValidator.REQUIRED_LEGENDS + DeckValidator.MAIN_DECK_MIN_SIZE;
        List<String> cardIds = new ArrayList<>(target);
        for (Card legend : bestLegends) {
            cardIds.add(legend.getId());
        }
        for (int copies = 0; copies < DeckValidator.MAX_COPIES_PER_CARD && cardIds.size() < target; copies++) {
            for (Card card : pool) {
                if (cardIds.size() >= target) {
                    break;
                }
                cardIds.add(card.getId());
            }
        }
        return new Deck(name, userId, cardIds);
    }
}
