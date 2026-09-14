package com.cyberpunktcg.api.dto;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardRarity;
import com.cyberpunktcg.domain.card.CardType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Représentation exacte d'une entrée de data/cards.json. */
public record CardImportData(
        @NotBlank @Size(max = 180) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String id,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 200) String subtitle,
        @NotNull CardType type,
        @NotNull CardColor color,
        @NotNull @Min(0) @Max(20) Integer ram,
        @Min(0) @Max(50) Integer cost,
        @Min(0) @Max(100) Integer power,
        @Min(0) @Max(100) Integer streetCred,
        @NotNull List<@NotBlank @Size(max = 80) String> tags,
        @NotNull List<@NotNull CardKeyword> keywords,
        @NotNull @Size(max = 10_000) String text,
        @NotNull List<@NotBlank @Size(max = 2_000) String> abilities,
        @Size(max = 2_048) String imageUrl,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Z0-9]+$") String setCode,
        @NotBlank @Size(max = 32) String collectorNumber,
        CardRarity rarity
) {
    public Card toEntity() {
        return new Card(
                id,
                name,
                subtitle,
                type,
                color,
                ram,
                cost,
                power,
                streetCred,
                tags,
                keywords,
                text,
                abilities,
                imageUrl,
                setCode,
                collectorNumber,
                rarity
        );
    }
}
