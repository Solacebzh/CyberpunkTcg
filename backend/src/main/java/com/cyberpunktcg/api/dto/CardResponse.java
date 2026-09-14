package com.cyberpunktcg.api.dto;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardRarity;
import com.cyberpunktcg.domain.card.CardType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Contrat REST aligné champ pour champ sur card-schema.json. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CardResponse(
        String id,
        String name,
        String subtitle,
        CardType type,
        CardColor color,
        int ram,
        Integer cost,
        Integer power,
        Integer streetCred,
        List<String> tags,
        List<CardKeyword> keywords,
        String text,
        List<String> abilities,
        String imageUrl,
        String setCode,
        String collectorNumber,
        CardRarity rarity
) {
    public static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                card.getName(),
                card.getSubtitle(),
                card.getType(),
                card.getColor(),
                card.getRam(),
                card.getCost(),
                card.getPower(),
                card.getStreetCred(),
                card.getTags(),
                card.getKeywords(),
                card.getText(),
                card.getAbilities(),
                card.getImageUrl(),
                card.getSetCode(),
                card.getCollectorNumber(),
                card.getRarity()
        );
    }
}
