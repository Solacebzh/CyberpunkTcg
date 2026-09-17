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

Le branchement à la source réelle des cartes et les tests d'import sont pris en charge par la feature 02.

## 🟡 Feature 02 — Données de cartes *(PR en revue)*

- contrat JSON canonique dans `backend/src/main/resources/schema/card-schema.json`, avec `abilities[]` ;
- scraper de l'API NetDeck utilisée par `cyberpunktcg.com` : pagination, cache, normalisation et tests pytest ;
- entité JPA `Card`, repository et import idempotent de 5 cartes au démarrage ;
- API `GET /api/cards` (filtres type/couleur), `GET /api/cards/{id}` et `GET /api/cards/stats` ;
- frontend aligné sur le schéma, affichage des stats/tags/effets depuis l'API ;
- documentation de référence dans `DATA-MODEL.md` et règles confirmées.

## 🔜 Feature 03 — Comptes et decks

- compte joueur minimal (pseudo + jeton de session, pas de collecte inutile de données) ;
- deck builder : 3 Legends uniques, 40-50 cartes, max 3 copies, **plafonds de RAM par couleur calculés serveur** ;
- persistance des decks + validation serveur (`R1`), messages d'erreur explicites ;
- introduction de **Flyway** pour remplacer `ddl-auto=update`.

## ✅ Mini-Feature 10A — UX du deckbuilder *(livrée)*

- « + Nouveau Deck » (en-tête, « + » de la liste « Mes Decks », barre d'outils du
  deck) appelle `deckStore.resetDeck()` : liste de cartes vidée, nom remis à
  `DEFAULT_DECK_NAME`, éditeur détaché du deck sauvegardé — le formulaire
  n'affiche plus les cartes du deck précédent ;
- bouton « Vider le deck » explicite au-dessus de la liste des cartes (désactivé
  quand le deck est vide), compteur de cartes et copie `n/3` par carte du catalogue ;
- import textuel : le parseur compare `card.name` **et** `card.subtitle`
  (`3 Adam Smasher: Metal Over Meat`, `-`, `|`, `(sous-titre)` acceptés), refuse
  un sous-titre inconnu plutôt que de deviner, et signale les lignes restées
  ambiguës ; les sous-titres sont désormais affichés partout dans l'UI.

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

Invariants confirmés du moteur : victoire à 7 Gigs en début de tour, une vente par tour et réactions QUICK uniquement.

## 🔜 Feature 06 — Expérience de jeu

- plateau : Fixer Area, Legends, Field, Eddies, Gigs alliés/adverses, journal de partie ;
  *(mini-feature livrée : la disposition du plateau suit le tapis officiel — grille CSS
  `.playmat-grid`, 3 slots de Legends, colonne Fixer d20 → d4, bandeau Rival/Friendly Gigs ;
  voir `FRONTEND-ARCHITECTURE.md` § 6 bis)* ;
- animations GSAP : pose, attaque, vols de Gig, retournement de Legend ;
- mode spectateur, historique et rejeu d'une partie ;
- responsive et accessibilité (contraste, navigation clavier, préférence `prefers-reduced-motion`).

## Idées après la V1

- mode 3-4 joueurs (le moteur est déjà séparé de la couche transport) ;
- bot d'entraînement qui rejoue des parties enregistrées ;
- statistiques de decks et de parties entre amis ;
- broker externe (RabbitMQ) si plusieurs instances backend.
