package com.cyberpunktcg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée du serveur autoritaire Cyberpunk TCG Online.
 *
 * <p>Le serveur est la seule source de vérité : le client n'envoie que des intentions
 * (« je joue cette carte », « j'attaque avec cette unité ») et ne calcule jamais l'état de jeu.</p>
 */
@SpringBootApplication
public class CyberpunkTcgApplication {

    public static void main(String[] args) {
        SpringApplication.run(CyberpunkTcgApplication.class, args);
    }
}
