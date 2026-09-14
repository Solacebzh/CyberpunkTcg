package com.cyberpunktcg.service;

import com.cyberpunktcg.api.dto.CardImportData;
import com.cyberpunktcg.api.dto.CardResponse;
import com.cyberpunktcg.api.dto.CardStatsResponse;
import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.repository.CardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class CardService {

    static final String BUNDLED_CARDS_PATH = "data/cards.json";

    private final CardRepository repository;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public CardService(CardRepository repository, ObjectMapper objectMapper, Validator validator) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public List<CardResponse> findCards(String rawType, String rawColor) {
        CardType type = parseType(rawType);
        CardColor color = parseColor(rawColor);

        List<Card> cards;
        if (type != null && color != null) {
            cards = repository.findByTypeAndColorOrderByNameAscIdAsc(type, color);
        } else if (type != null) {
            cards = repository.findByTypeOrderByNameAscIdAsc(type);
        } else if (color != null) {
            cards = repository.findByColorOrderByNameAscIdAsc(color);
        } else {
            cards = repository.findAllByOrderByNameAscIdAsc();
        }
        return cards.stream().map(CardResponse::from).toList();
    }

    public CardResponse findById(String id) {
        return repository.findById(id)
                .map(CardResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carte introuvable : " + id));
    }

    public CardStatsResponse stats() {
        Map<String, Long> byType = new LinkedHashMap<>();
        for (CardType type : CardType.values()) {
            byType.put(type.value(), repository.countByType(type));
        }
        Map<String, Long> byColor = new LinkedHashMap<>();
        for (CardColor color : CardColor.values()) {
            byColor.put(color.value(), repository.countByColor(color));
        }
        return new CardStatsResponse(repository.count(), byType, byColor);
    }

    /**
     * Importe le fichier embarqué uniquement si le catalogue est vide.
     * Un second démarrage est donc idempotent et ne remplace pas des données déjà chargées.
     */
    @Transactional
    public int importBundledCardsIfEmpty() {
        if (repository.count() > 0) {
            return 0;
        }

        List<CardImportData> data;
        ClassPathResource resource = new ClassPathResource(BUNDLED_CARDS_PATH);
        try (InputStream input = resource.getInputStream()) {
            data = objectMapper.readValue(input, new TypeReference<List<CardImportData>>() { });
        } catch (IOException error) {
            throw new IllegalStateException("Impossible de lire classpath:" + BUNDLED_CARDS_PATH, error);
        }

        validateImport(data);
        repository.saveAll(data.stream().map(CardImportData::toEntity).toList());
        repository.flush();
        return data.size();
    }

    private void validateImport(List<CardImportData> data) {
        if (data.isEmpty()) {
            throw new IllegalStateException("Le catalogue de cartes embarqué est vide");
        }
        Set<String> ids = new HashSet<>();
        for (CardImportData card : data) {
            Set<ConstraintViolation<CardImportData>> violations = validator.validate(card);
            if (!violations.isEmpty()) {
                String details = violations.stream()
                        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .sorted()
                        .findFirst()
                        .orElse("donnée invalide");
                throw new IllegalStateException("Carte invalide " + card.id() + " : " + details);
            }
            if (!ids.add(card.id())) {
                throw new IllegalStateException("Identifiant de carte dupliqué : " + card.id());
            }
        }
    }

    private CardType parseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        try {
            return CardType.fromValue(rawType);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type de carte invalide : " + rawType, error);
        }
    }

    private CardColor parseColor(String rawColor) {
        if (rawColor == null || rawColor.isBlank()) {
            return null;
        }
        try {
            return CardColor.fromValue(rawColor);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Couleur de carte invalide : " + rawColor, error);
        }
    }
}
