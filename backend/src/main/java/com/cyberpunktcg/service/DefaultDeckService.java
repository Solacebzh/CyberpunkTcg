package com.cyberpunktcg.service;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.lobby.LobbyException;
import com.cyberpunktcg.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Construit et valide les decks utilisés par le lobby.
 *
 * <p>Le deck par défaut suit la règle du cahier des charges : 3 Legends
 * (zone spéciale) + 10 Units (pioche). Le catalogue est lu en base, déjà
 * alimenté par {@code CardDataInitializer}.</p>
 */
@Service
public class DefaultDeckService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDeckService.class);

    /** Nombre de Legends attendues dans un deck (zone Legends fixe à 3). */
    public static final int REQUIRED_LEGENDS = 3;
    /** Nombre minimum de cartes non-Legend (il faut pouvoir distribuer 6 cartes). */
    public static final int REQUIRED_NON_LEGENDS = 10;

    private final CardRepository cardRepository;

    public DefaultDeckService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Retourne le deck à utiliser : le deck fourni s'il est valide, sinon le
     * deck par défaut construit depuis le catalogue.
     */
    @Transactional(readOnly = true)
    public List<String> resolveDeck(List<String> requestedCardIds) {
        if (requestedCardIds == null || requestedCardIds.isEmpty()) {
            return defaultDeck();
        }
        return validateDeck(requestedCardIds);
    }

    /**
     * Deck par défaut : les 3 premières Legends + les 10 premières Units du
     * catalogue (ordre déterministe par nom puis identifiant).
     */
    @Transactional(readOnly = true)
    public List<String> defaultDeck() {
        List<Card> legends = cardRepository.findByTypeOrderByNameAscIdAsc(CardType.LEGEND);
        List<Card> units = cardRepository.findByTypeOrderByNameAscIdAsc(CardType.UNIT);
        if (legends.size() < REQUIRED_LEGENDS || units.size() < REQUIRED_NON_LEGENDS) {
            throw new IllegalStateException("Catalogue incomplet : impossible de bâtir le deck par défaut ("
                    + legends.size() + " legends, " + units.size() + " units)");
        }
        List<String> deck = new ArrayList<>(REQUIRED_LEGENDS + REQUIRED_NON_LEGENDS);
        legends.stream().limit(REQUIRED_LEGENDS).forEach(card -> deck.add(card.getId()));
        units.stream().limit(REQUIRED_NON_LEGENDS).forEach(card -> deck.add(card.getId()));
        log.debug("Deck par défaut généré : {} cartes", deck.size());
        return List.copyOf(deck);
    }

    /**
     * Valide un deck personnalisé : toutes les cartes existent, exactement
     * 3 Legends et au moins 10 autres cartes, pas de doublon d'identifiant.
     */
    @Transactional(readOnly = true)
    public List<String> validateDeck(List<String> requestedCardIds) {
        List<String> ids = requestedCardIds.stream().filter(id -> id != null && !id.isBlank()).toList();
        if (ids.size() != new HashSet<>(ids).size()) {
            throw new LobbyException("DECK_INVALID", "Le deck contient des cartes en double");
        }
        List<Card> cards = cardRepository.findAllById(ids);
        if (cards.size() != ids.size()) {
            Set<String> found = new HashSet<>(cards.stream().map(Card::getId).toList());
            String missing = ids.stream().filter(id -> !found.contains(id)).findFirst().orElse("?");
            throw new LobbyException("DECK_INVALID", "Carte inconnue dans le deck : " + missing);
        }
        long legends = cards.stream().filter(card -> card.getType() == CardType.LEGEND).count();
        int total = ids.size();
        if (legends != REQUIRED_LEGENDS) {
            throw new LobbyException("DECK_INVALID",
                    "Le deck doit contenir exactement 3 Legends (reçu " + legends + ")");
        }
        if (total - legends < REQUIRED_NON_LEGENDS) {
            throw new LobbyException("DECK_INVALID",
                    "Le deck doit contenir au moins 10 cartes non-Legend (reçu " + (total - legends) + ")");
        }
        return List.copyOf(ids);
    }
}
