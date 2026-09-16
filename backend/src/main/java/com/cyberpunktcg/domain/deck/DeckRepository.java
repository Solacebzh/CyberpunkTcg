package com.cyberpunktcg.domain.deck;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Accès aux decks sauvegardés. Toutes les recherches sont filtrées par
 * {@code userId} : un joueur ne peut ni lire ni modifier les decks d'un autre.
 */
@Repository
public interface DeckRepository extends JpaRepository<Deck, Long> {

    /** « Mes decks », triés par nom puis identifiant (ordre stable). */
    List<Deck> findByUserIdOrderByNameAscIdAsc(Long userId);

    /** Un deck uniquement s'il appartient bien au joueur courant. */
    Optional<Deck> findByIdAndUserId(Long id, Long userId);
}
