package com.cyberpunktcg.repository;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardRepository extends JpaRepository<Card, String> {
    List<Card> findAllByOrderByNameAscIdAsc();
    List<Card> findByTypeOrderByNameAscIdAsc(CardType type);
    List<Card> findByColorOrderByNameAscIdAsc(CardColor color);
    List<Card> findByTypeAndColorOrderByNameAscIdAsc(CardType type, CardColor color);
    long countByType(CardType type);
    long countByColor(CardColor color);
}
