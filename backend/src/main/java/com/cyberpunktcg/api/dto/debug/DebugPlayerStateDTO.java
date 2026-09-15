package com.cyberpunktcg.api.dto.debug;

import com.cyberpunktcg.api.dto.ws.CardInstanceDTO;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.game.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * État complet et <strong>non masqué</strong> d'un joueur, pour le debug
 * ({@code GET /api/debug/game/{gameId}} et
 * {@code GET /api/debug/game/{gameId}/player/{playerId}}).
 *
 * @param playerId            identifiant du joueur
 * @param name                nom affiché
 * @param eddies              Eddies disponibles
 * @param gigs                valeurs des Gigs contrôlés
 * @param gigDice             type de dé de chaque Gig (aligné sur {@code gigs})
 * @param gigCount            nombre de Gigs
 * @param streetCred          Street Cred (somme des Gigs)
 * @param fixerDice           dés Gig restants dans la Fixer Area
 * @param legendsReady        Legends encore inclinables pour gagner un Eddie
 * @param legendsSpent        Legends déjà inclinées
 * @param ramCeilings         plafond de RAM par couleur (dérivé des Legends)
 * @param hasLegendCeiling    {@code true} si un plafond de RAM s'applique
 * @param deckCount           taille de la pioche
 * @param hand                main complète (visible en debug)
 * @param deck                pioche complète (visible en debug)
 * @param field               Units et Gears sur le terrain
 * @param trash               défausse
 * @param eddiesArea          cartes vendues (face cachée)
 * @param legendsArea         Legends (inclinées ou non)
 */
public record DebugPlayerStateDTO(
        String playerId,
        String name,
        int eddies,
        int availableEddies,
        int costDiscount,
        boolean hasSoldThisTurn,
        List<Integer> gigs,
        List<String> gigDice,
        int gigCount,
        int streetCred,
        List<String> fixerDice,
        int legendsReady,
        int legendsSpent,
        Map<String, Integer> ramCeilings,
        boolean hasLegendCeiling,
        int deckCount,
        List<CardInstanceDTO> hand,
        List<CardInstanceDTO> deck,
        List<CardInstanceDTO> field,
        List<CardInstanceDTO> trash,
        List<CardInstanceDTO> eddiesArea,
        List<CardInstanceDTO> legendsArea
) {
    public static DebugPlayerStateDTO from(Player player) {
        Map<String, Integer> ceilings = new LinkedHashMap<String, Integer>();
        for (CardColor color : CardColor.values()) {
            ceilings.put(color.value(), player.ramCeilingFor(color));
        }
        return new DebugPlayerStateDTO(
                player.getId(),
                player.getName(),
                player.getEddies(),
                player.getAvailableEddies(),
                player.getCostDiscount(),
                player.hasSoldThisTurn(),
                List.copyOf(player.getGigs()),
                List.copyOf(player.getGigDice()),
                player.getGigCount(),
                player.getStreetCred(),
                List.copyOf(player.getFixerDice()),
                player.legendsAvailableForEddies().size(),
                player.countSpentLegends(),
                ceilings,
                player.hasLegendCeiling(),
                player.getDeck().size(),
                player.getHand().stream().map(CardInstanceDTO::from).toList(),
                player.getDeck().stream().map(CardInstanceDTO::from).toList(),
                player.getField().stream().map(CardInstanceDTO::from).toList(),
                player.getTrash().stream().map(CardInstanceDTO::from).toList(),
                player.getEddiesArea().stream().map(CardInstanceDTO::from).toList(),
                player.getLegendsArea().stream().map(CardInstanceDTO::from).toList());
    }
}
