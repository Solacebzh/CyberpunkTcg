package com.cyberpunktcg.domain.deck;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deck sauvegardé par un joueur (Mini-Feature 9C — persistance des decks).
 *
 * <p>Un deck appartient à un compte ({@link #userId}) et contient la liste
 * ordonnée des identifiants de cartes du catalogue. Les identifiants peuvent se
 * répéter : le Main Deck autorise jusqu'à
 * {@link com.cyberpunktcg.engine.DeckValidator#MAX_COPIES_PER_CARD} exemplaires
 * d'une même carte. La collection est donc stockée <em>avec son ordre</em>
 * ({@code @OrderColumn}) dans la table {@code deck_cards}.</p>
 *
 * <p>Aucune contrainte de clé étrangère vers {@code cards} : le deck reste
 * lisible même si le catalogue évolue, la cohérence étant vérifiée par
 * {@link DeckService} au moment de la sauvegarde.</p>
 */
@Entity
@Table(name = "decks", indexes = {
        @Index(name = "idx_decks_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Deck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom choisi par le joueur (affiché dans « Mes Decks »). */
    @Column(nullable = false, length = 80)
    private String name;

    /** Propriétaire du deck : identifiant du compte {@code users.id}. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /**
     * Identifiants de cartes du deck (Legends + Main Deck), dans l'ordre
     * choisi par le joueur et avec les exemplaires répétés.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "deck_cards", joinColumns = @JoinColumn(name = "deck_id"))
    @OrderColumn(name = "position")
    @Column(name = "card_id", length = 180, nullable = false)
    private List<String> cardIds = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Deck(String name, Long userId, List<String> cardIds) {
        this.name = name;
        this.userId = userId;
        this.cardIds = cardIds != null ? new ArrayList<>(cardIds) : new ArrayList<>();
    }

    /** Copie défensive : la liste persistée n'est pas modifiable de l'extérieur. */
    public List<String> getCardIds() {
        return cardIds != null ? List.copyOf(cardIds) : List.of();
    }

    /**
     * Remplace le contenu du deck.
     *
     * <p>On vide puis remplit la collection déjà mappée au lieu de changer sa
     * référence : c'est le comportement attendu par Hibernate pour une
     * {@code @ElementCollection} d'une entité gérée (réécriture des lignes de
     * {@code deck_cards}), et cela accepte aussi bien une liste immuable qu'un
     * {@code null}.</p>
     */
    public void setCardIds(List<String> cardIds) {
        if (this.cardIds == null) {
            this.cardIds = new ArrayList<>();
        }
        this.cardIds.clear();
        if (cardIds != null) {
            this.cardIds.addAll(cardIds);
        }
    }

    /** Nombre total de cartes du deck (Legends comprises, exemplaires compris). */
    public int size() {
        return cardIds != null ? cardIds.size() : 0;
    }
}
