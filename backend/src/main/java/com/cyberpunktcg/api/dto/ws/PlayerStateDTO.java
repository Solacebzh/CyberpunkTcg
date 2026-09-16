package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.game.Player;

import java.util.List;

/**
 * Vue sérialisable de l'état d'un joueur, destinée à UNE seule paire d'yeux.
 *
 * <p>Construite à partir d'un {@link com.cyberpunktcg.domain.game.GameState}
 * déjà masqué : la main de l'adversaire ne contient que des cartes
 * {@code hidden}, et la taille de sa pioche reste publique.</p>
 *
 * <p>{@code gigs}, {@code gigDice} et {@code gigDieIds} sont alignés index par
 * index : la valeur du dé actif, le type du dé qui l'a produite ({@code "d8"}, ou
 * {@code "?"} pour un Gig injecté hors lancer) et son identifiant stable —
 * celui-ci sert au choix des dés à voler (Mini-Feature 6, action
 * {@code STEAL_GIG}). {@code fixerDice} liste les dés <em>pas encore lancés</em> :
 * ils ne sont jamais volables.</p>
 */
public record PlayerStateDTO(
        String playerId,
        String name,
        boolean connected,
        int deckCount,
        List<CardInstanceDTO> hand,
        List<CardInstanceDTO> field,
        List<CardInstanceDTO> trash,
        List<CardInstanceDTO> eddiesArea,
        List<CardInstanceDTO> legendsArea,
        List<Integer> gigs,
        List<String> gigDice,
        List<String> gigDieIds,
        List<String> fixerDice,
        int gigCount,
        int streetCred,
        int eddies,
        int availableEddies,
        int costDiscount,
        boolean hasSoldThisTurn
) {
    public static PlayerStateDTO from(Player player, boolean connected) {
        return new PlayerStateDTO(
                player.getId(),
                player.getName(),
                connected,
                player.getDeck().size(),
                player.getHand().stream().map(CardInstanceDTO::from).toList(),
                player.getField().stream().map(CardInstanceDTO::from).toList(),
                player.getTrash().stream().map(CardInstanceDTO::from).toList(),
                player.getEddiesArea().stream().map(CardInstanceDTO::from).toList(),
                player.getLegendsArea().stream().map(CardInstanceDTO::from).toList(),
                List.copyOf(player.getGigs()),
                List.copyOf(player.getGigDice()),
                List.copyOf(player.getGigDieIds()),
                List.copyOf(player.getFixerDice()),
                player.getGigCount(),
                player.getStreetCred(),
                player.getEddies(),
                player.getAvailableEddies(),
                player.getCostDiscount(),
                player.hasSoldThisTurn());
    }
}
