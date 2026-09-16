package com.cyberpunktcg.domain.deck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Payload de création / mise à jour d'un deck ({@code POST} et {@code PUT /api/decks}).
 *
 * <p>{@code cardIds} contient les identifiants du catalogue dans l'ordre du deck,
 * exemplaires compris (jusqu'à 3 copies d'une même carte du Main Deck). Les
 * règles officielles (3 Legends uniques, Main Deck 40-50, max 3 copies, plafonds
 * de RAM par couleur) sont vérifiées par le service, pas par ces annotations :
 * elles ne bornent que la forme de la requête.</p>
 */
public record DeckRequest(

        @NotBlank(message = "Le nom du deck est obligatoire")
        @Size(max = 80, message = "Le nom du deck ne peut pas dépasser 80 caractères")
        String name,

        @NotNull(message = "La liste des cartes du deck est obligatoire")
        @Size(min = 1, max = 100, message = "Un deck contient au maximum 100 identifiants de cartes")
        List<@NotBlank(message = "Un identifiant de carte ne peut pas être vide") String> cardIds
) {
}
