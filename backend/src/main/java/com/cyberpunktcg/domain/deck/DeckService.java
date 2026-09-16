package com.cyberpunktcg.domain.deck;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.deck.dto.DeckRequest;
import com.cyberpunktcg.domain.deck.dto.DeckResponse;
import com.cyberpunktcg.domain.user.User;
import com.cyberpunktcg.domain.user.UserRepository;
import com.cyberpunktcg.engine.DeckValidationResult;
import com.cyberpunktcg.engine.DeckValidator;
import com.cyberpunktcg.repository.CardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistance des decks d'un joueur (Mini-Feature 9C).
 *
 * <p>Règle structurante : la validation officielle
 * ({@link DeckValidator} — 3 Legends uniques, Main Deck de 40 à 50 cartes,
 * 3 exemplaires maximum, plafonds de RAM par couleur) est appliquée
 * <strong>avant</strong> toute écriture en base. Un deck invalide lève
 * {@link DeckValidationException}, traduite en {@code 400 Bad Request} avec la
 * liste des infractions : rien n'est persisté.</p>
 *
 * <p>Toutes les opérations sont bornées au joueur courant (identifiant résolu
 * depuis le JWT) : un deck d'un autre compte est traité comme inexistant
 * ({@code 404}) plutôt que comme interdit, pour ne rien révéler du catalogue
 * des autres joueurs.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeckService {

    /** Garde-fou sur la taille d'une requête : au-delà, le deck est de toute façon illégal. */
    static final int MAX_SUBMITTED_CARDS = 100;

    private final DeckRepository deckRepository;
    private final CardRepository cardRepository;
    private final UserRepository userRepository;

    /** Liste des decks du joueur courant (« Mes Decks »). */
    @Transactional(readOnly = true)
    public List<DeckResponse> listDecks(String username) {
        Long userId = requireUserId(username);
        return deckRepository.findByUserIdOrderByNameAscIdAsc(userId)
                .stream()
                .map(DeckResponse::from)
                .toList();
    }

    /** Détail d'un deck du joueur courant. */
    @Transactional(readOnly = true)
    public DeckResponse getDeck(String username, Long deckId) {
        Long userId = requireUserId(username);
        return DeckResponse.from(requireOwnedDeck(deckId, userId));
    }

    /** Crée un deck après validation des règles officielles. */
    @Transactional
    public DeckResponse createDeck(String username, DeckRequest request) {
        Long userId = requireUserId(username);
        String name = requireName(request);
        List<String> cardIds = validateBeforeSave(request);

        Deck saved = deckRepository.save(new Deck(name, userId, cardIds));
        log.info("Deck '{}' (#{} créé) sauvegardé pour {} : {} cartes", name, saved.getId(), username, saved.size());
        return DeckResponse.from(saved);
    }

    /** Remplace le nom et le contenu d'un deck existant, après re-validation. */
    @Transactional
    public DeckResponse updateDeck(String username, Long deckId, DeckRequest request) {
        Long userId = requireUserId(username);
        Deck deck = requireOwnedDeck(deckId, userId);
        String name = requireName(request);
        List<String> cardIds = validateBeforeSave(request);

        deck.setName(name);
        deck.setCardIds(cardIds);
        Deck saved = deckRepository.save(deck);
        log.info("Deck '{}' (#{} modifié) mis à jour pour {} : {} cartes",
                name, saved.getId(), username, saved.size());
        return DeckResponse.from(saved);
    }

    /** Supprime un deck du joueur courant. */
    @Transactional
    public void deleteDeck(String username, Long deckId) {
        Long userId = requireUserId(username);
        Deck deck = requireOwnedDeck(deckId, userId);
        deckRepository.delete(deck);
        log.info("Deck '{}' (#{} supprimé) retiré du compte {}", deck.getName(), deckId, username);
    }

    // ---------------------------------------------------------------------
    // Validation : appliquée avant toute sauvegarde
    // ---------------------------------------------------------------------

    /**
     * Contrôle la forme de la requête puis applique les règles officielles.
     *
     * @return identifiants normalisés, dans l'ordre du deck, exemplaires compris
     * @throws DeckValidationException si une carte est inconnue ou si le deck est illégal
     */
    private List<String> validateBeforeSave(DeckRequest request) {
        List<String> cardIds = normalizeCardIds(request != null ? request.cardIds() : null);
        List<Card> cards = resolveCards(cardIds);
        DeckValidationResult result = DeckValidator.validate(cards);
        if (!result.isValid()) {
            log.debug("Sauvegarde refusée ({} infraction(s)) : {}", result.getErrors().size(), result.getErrors());
            throw new DeckValidationException(result.getErrors());
        }
        return cardIds;
    }

    private String requireName(DeckRequest request) {
        String name = request != null && request.name() != null ? request.name().trim() : "";
        if (name.isEmpty()) {
            throw new DeckValidationException(List.of("Le nom du deck est obligatoire"));
        }
        if (name.length() > 80) {
            throw new DeckValidationException(List.of("Le nom du deck ne peut pas dépasser 80 caractères"));
        }
        return name;
    }

    /** Nettoie la liste reçue : espaces retirés, entrées vides ignorées. */
    private List<String> normalizeCardIds(List<String> rawIds) {
        List<String> cardIds = new ArrayList<>();
        if (rawIds != null) {
            for (String rawId : rawIds) {
                if (rawId == null) {
                    continue;
                }
                String id = rawId.trim();
                if (!id.isEmpty()) {
                    cardIds.add(id);
                }
            }
        }
        if (cardIds.isEmpty()) {
            throw new DeckValidationException(List.of("Le deck doit contenir au moins une carte"));
        }
        if (cardIds.size() > MAX_SUBMITTED_CARDS) {
            throw new DeckValidationException(List.of(
                    "Le deck ne peut pas contenir plus de " + MAX_SUBMITTED_CARDS
                            + " cartes (reçu : " + cardIds.size() + ")"));
        }
        return List.copyOf(cardIds);
    }

    /**
     * Résout les identifiants en {@link Card} du catalogue en conservant les
     * doublons : {@code findAllById} déduplique, or la limite de 3 exemplaires
     * se compte sur la liste réellement soumise.
     */
    private List<Card> resolveCards(List<String> cardIds) {
        Set<String> distinctIds = new LinkedHashSet<>(cardIds);
        Map<String, Card> catalog = new HashMap<>();
        for (Card card : cardRepository.findAllById(distinctIds)) {
            catalog.put(card.getId(), card);
        }

        List<Card> resolved = new ArrayList<>(cardIds.size());
        Set<String> unknownIds = new LinkedHashSet<>();
        for (String id : cardIds) {
            Card card = catalog.get(id);
            if (card != null) {
                resolved.add(card);
            } else {
                unknownIds.add(id);
            }
        }
        if (!unknownIds.isEmpty()) {
            throw new DeckValidationException(List.of(
                    "Carte(s) inconnue(s) dans le deck : " + String.join(", ", unknownIds)));
        }
        return resolved;
    }

    // ---------------------------------------------------------------------
    // Portée « joueur courant »
    // ---------------------------------------------------------------------

    private Long requireUserId(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentification requise");
        }
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Compte introuvable pour le jeton fourni"));
    }

    private Deck requireOwnedDeck(Long deckId, Long userId) {
        return deckRepository.findByIdAndUserId(deckId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Deck introuvable : " + deckId));
    }
}
