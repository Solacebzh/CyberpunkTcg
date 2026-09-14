package com.cyberpunktcg.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class CardDataInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CardDataInitializer.class);

    private final CardService cardService;

    public CardDataInitializer(CardService cardService) {
        this.cardService = cardService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int imported = cardService.importBundledCardsIfEmpty();
        if (imported > 0) {
            LOGGER.info("Catalogue initialisé avec {} cartes depuis classpath:data/cards.json", imported);
        } else {
            LOGGER.debug("Catalogue déjà initialisé, import de démarrage ignoré");
        }
    }
}
