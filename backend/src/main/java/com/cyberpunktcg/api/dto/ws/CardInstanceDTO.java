package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;

import java.util.List;
import java.util.UUID;

/**
 * Vue sérialisable d'un exemplaire de carte.
 *
 * <p>Pour les exemplaires secrets de l'adversaire, le serveur envoie des
 * cartes masquées ({@code cardId = "hidden"}, caractéristiques effacées) ;
 * le client n'a donc jamais assez d'informations pour tricher.</p>
 */
public record CardInstanceDTO(
        UUID instanceId,
        String cardId,
        String name,
        CardType type,
        CardColor color,
        Integer baseCost,
        Integer cost,
        Integer power,
        Integer powerBonus,
        Integer damage,
        Integer streetCredThreshold,
        List<String> keywords,
        List<String> abilities,
        String ownerId,
        String zone,
        boolean faceDown,
        boolean exhausted,
        boolean summoningSickness,
        UUID attachedTo,
        List<UUID> attachments
) {
    public static CardInstanceDTO from(CardInstance card) {
        return new CardInstanceDTO(
                card.getInstanceId(),
                card.getCardId(),
                card.getName(),
                card.getType(),
                card.getColor(),
                card.getBaseCost(),
                card.getEffectiveCost(),
                card.getEffectivePower(),
                card.getPowerBonus(),
                card.getDamage(),
                card.getStreetCredThreshold(),
                card.getKeywords().stream().map(keyword -> keyword.value()).toList(),
                card.getAbilities(),
                card.getOwnerId(),
                card.getZone().name(),
                card.isFaceDown(),
                card.isExhausted(),
                card.isSummoningSickness(),
                card.getAttachedTo(),
                card.getAttachments());
    }
}
