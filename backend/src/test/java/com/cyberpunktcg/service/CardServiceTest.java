package com.cyberpunktcg.service;

import com.cyberpunktcg.repository.CardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CardServiceTest {

    @Autowired
    private CardService cardService;

    @Autowired
    private CardRepository repository;

    @Test
    void bundledImport_shouldPopulateAnEmptyDatabaseAndThenBeIdempotent() {
        repository.deleteAll();
        repository.flush();

        int imported = cardService.importBundledCardsIfEmpty();

        assertThat(imported).isPositive();
        assertThat(repository.count()).isEqualTo(imported);
        assertThat(cardService.importBundledCardsIfEmpty()).isZero();
        assertThat(repository.count()).isEqualTo(imported);
    }
}
