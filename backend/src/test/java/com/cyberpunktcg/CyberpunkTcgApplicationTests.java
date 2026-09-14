package com.cyberpunktcg;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Vérifie que le contexte Spring démarre (profil {@code test} → H2 en mémoire).
 * Aucun PostgreSQL n'est requis pour lancer {@code mvn test}.
 */
@ActiveProfiles("test")
@SpringBootTest
class CyberpunkTcgApplicationTests {

    @Test
    void contextLoads() {
        // Le test échoue si un bean de la configuration de base est introuvable.
    }
}
