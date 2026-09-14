# Roadmap

Une PR par feature, jamais mergée sans relecture. Chaque feature part de `main` à jour.

## ✅ Feature 01 — Setup du monorepo *(livrée)*

**Contenu**

- monorepo `backend/` + `frontend/` + `scraper/` + `docs/` + outillage (Docker Compose, Makefile, `.editorconfig`) ;
- backend Spring Boot 3.5 / Java 21 : starters Web, WebSocket (STOMP), Data JPA, Validation, PostgreSQL, Lombok ;
  endpoint `/ws`, contrôleurs `/api/health` et `/app/ping`, profil de test H2 ;
- frontend Vue 3 + TypeScript + Vite : Pinia, vue-router, TailwindCSS v4, GSAP, `@stomp/stompjs`,
  proxy de dev `/api` et `/ws`, page d'accueil « diagnostic » et placeholders d'écrans ;
- scraper Python : pipeline `fetch → parse → models → export`, 31 tests, CLI, exports JSON/JSONL/manifeste ;
- documentation complète (architecture, prise en main, modèle de données, règles, protocole STOMP, schéma de carte).

**Reste à faire dans cette feature (suivi)**

- `mvn test` et `mvn spring-boot:run` à valider sur une machine avec Java 21 (non exécutables dans
  l'environnement de génération), puis ajuster si nécessaire ;
- brancher les sélecteurs réels du parser HTML du site (reporté en feature 02).

## 🔜 Feature 02 — Données de cartes

- finaliser `site.py` sur le HTML réel (bloc JSON embarqué ou DOM) ;
- endpoint `POST /api/cards/import` (import idempotent depuis `cards.json`) + `GET /api/cards` (filtres, pagination) ;
- entités JPA `Card` + repository, chargement en base au démarrage si la table est vide ;
- images : rapatriement dans notre stockage (ou cache) plutôt que lien direct vers le CDN tiers ;
- tests d'intégration : import d'un jeu de 8 cartes puis lecture via l'API.

## 🔜 Feature 03 — Comptes et decks

- compte joueur minimal (pseudo + jeton de session, pas de collecte inutile de données) ;
- deck builder : 3 Legends uniques, 40-50 cartes, max 3 copies, **plafonds de RAM par couleur calculés serveur** ;
- persistance des decks + validation serveur (`R1`), messages d'erreur explicites ;
- introduction de **Flyway** pour remplacer `ddl-auto=update`.

## 🔜 Feature 04 — Lobby et partie 1v1

- salles avec code d'invitation, présence, prêts, choix du deck ;
- démarrage d'une partie : mise en place, tirage, mulligan, détermination du premier joueur ;
- service de partie en mémoire (`GameService`), diffusion d'états filtrés par destinataire sur `/topic/game.{id}` ;
- gestion des déconnexions/reconnexions et resynchronisation complète.

## 🔜 Feature 05 — Moteur de règles

Implémentation de `R1` → `R10` (voir `game-rules.md` §8), avec le moteur en code pur (hors Spring) et
couverture de tests élevée. Ordre prévu :

1. `R2` mise en place / pioche / mulligan ;
2. `R3` phases et redressement ;
3. `R4` économie (Eddies, Street Cred) ;
4. `R5` pose de cartes, ciblage, Gear, Legends ;
5. `R6` combat complet ;
6. `R7` vol de Gigs et condition de victoire ;
7. `R8` fenêtre de réaction (QUICK) et pile de résolution ;
8. `R9` effets de cartes (petit langage d'effets data-driven) ;
9. `R10` rejeu déterministe (graine journalisée).

Paramètres de règles externalisés dans `config/game-rules.json` (6 ou 7 Gigs, vente libre ou unique…).

## 🔜 Feature 06 — Expérience de jeu

- plateau : Fixer Area, Legends, Field, Eddies, Gigs alliés/adverses, journal de partie ;
- animations GSAP : pose, attaque, vols de Gig, retournement de Legend ;
- mode spectateur, historique et rejeu d'une partie ;
- responsive et accessibilité (contraste, navigation clavier, préférence `prefers-reduced-motion`).

## Idées après la V1

- mode 3-4 joueurs (le moteur est déjà séparé de la couche transport) ;
- bot d'entraînement qui rejoue des parties enregistrées ;
- statistiques de decks et de parties entre amis ;
- broker externe (RabbitMQ) si plusieurs instances backend.
