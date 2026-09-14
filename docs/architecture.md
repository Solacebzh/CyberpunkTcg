# Architecture

## 1. Objectifs et contraintes

| Objectif | Comment on y répond |
| --- | --- |
| Jouer à distance avec des amis | Client web léger, temps réel via WebSocket STOMP, aucune installation pour les joueurs |
| Impossible de tricher | Serveur autoritaire : le client envoie des intentions, jamais des résultats |
| Ajouter des cartes sans redéployer | Cartes 100 % data-driven (JSON validé par un schéma) |
| Code jouable et testable | Séparation stricte moteur de règles / transport / persistance / affichage |
| Reprise facile du projet | Documentation à jour + une PR par sujet, jamais de gros commit fourre-tout |

## 2. Vue logique

```
                     ┌─────────────────────────────────────────────┐
   Navigateur ──────►│  frontend (Vue 3)                           │
                     │  views → components ── stores (Pinia)       │
                     │            services/api.ts  socket.ts       │
                     └───────┬──────────────────────┬──────────────┘
                             │ REST /api/**         │ STOMP /ws
                             ▼                      ▼
                     ┌─────────────────────────────────────────────┐
                     │  backend (Spring Boot)                      │
                     │  api/      contrôleurs + DTO (records)      │
                     │  service/  orchestration, transactions      │
                     │  engine/   moteur de règles (pur, testable) │
                     │  domain/   entités JPA + objets de jeu      │
                     │  repository/ Spring Data JPA                │
                     └───────┬──────────────────────┬──────────────┘
                             │ JDBC                 │ broadcast /topic/**
                             ▼                      ▼
                     ┌──────────────┐        (abonnés : les deux joueurs)
                     │ PostgreSQL   │
                     └──────────────┘
```

### Responsabilités

- **frontend** : afficher, animer, collecter les intentions du joueur. Aucun calcul de règle, aucun état de jeu
  « officiel » en mémoire (l'état local n'est qu'un cache d'affichage).
- **backend / api** : traduire le monde extérieur (REST, STOMP) en appels de service. Pas de règle de jeu ici.
- **backend / service** : transactions, chargement d'agrégats, publication des états. Pas de règle de jeu ici.
- **backend / engine** : **toutes** les règles. Code déterministe, sans dépendance à Spring ni à la base :
  entrée = `GameState` + `GameAction`, sortie = nouvel `GameState` + événements. C'est la partie la plus testée.
- **backend / repository** : persistance (cartes, comptes, decks). L'état d'une partie en cours vit en mémoire
  et n'est journalisé en base qu'en fin de partie (simplicité pour un jeu entre amis).
- **scraper** : produire `cards.json` conforme au schéma ; n'est jamais appelé au runtime de l'application.

## 3. Flux d'une action de jeu

```
Joueur clique « Jouer cette carte »
        │
        │  1. SEND /app/game.{id}.playCard   { cardInstanceId, targetInstanceId? }
        ▼
GameActionController  ──►  GameService.playCard(playerId, action)
        │                        │
        │                        │ 2. charge l'état, contrôle que c'est bien le tour du joueur,
        │                        │    délègue au moteur pour la validation complète
        │                        ▼
        │                  GameEngine.apply(state, action) → { newState, events }   (+ seeds RNG)
        │                        │
        │                        │ 3. le moteur rejette → événement ErrorEvent
        │                        │    le moteur accepte → nouvel état
        │                        ▼
        │                  StateBroadcaster  ──►  /topic/game.{id}     (les deux joueurs)
        │                                    ──►  /user/{session}/queue/errors (le fautif seulement)
        │
        │  4. Le client reçoit l'état complet, remplace son cache et anime les changements
        ▼
   Mise à jour de l'affichage (GSAP pour les transitions)
```

Points clés :

- **l'action porte une identité de joueur** déduite de la session STOMP, jamais du corps du message
  (sinon n'importe qui pourrait jouer à la place de l'adversaire) ;
- **le serveur diffuse un état complet**, pas un delta : le client reste simple et se resynchronise
  automatiquement après une reconnexion ;
- **les erreurs sont privées** (`/user/queue/errors`) : l'adversaire ne voit pas les actions illégales.

## 4. Autorité et sécurité (périmètre « entre amis »)

| Risque | Décision pour la V1 |
| --- | --- |
| Client modifié | Sans effet : le moteur ne fait jamais confiance au client |
| Jouer pour l'adversaire | Identité dérivée du canal STOMP, actions filtrées par `gameId` + `playerId` |
| Voir la main adverse | L'état diffusé est **filtré par destinataire** (vue « pour moi » / « pour l'adversaire ») |
| Rejouer une action | Chaque action porte un `sequence` strictement croissant, validé par le serveur |
| Comptes | Authentification simple (jeton de session) en feature 03 ; pas de mots de passe stockés par le jeu pour l'instant |
| Tricher avec le hasard | Le RNG vit côté serveur, la graine est journalisée pour pouvoir rejouer une partie |

## 5. Choix techniques et justifications

| Choix | Pourquoi |
| --- | --- |
| **Spring Boot 3.5 (et non 4.x)** | Dernière génération 3.x stable et ultra-documentée ; la migration 4.x se fera avec les outils de migration, une fois le moteur stabilisé |
| **STOMP sur WebSocket** | Modèle publish/subscribe standard, support natif Spring et client JS mûr (`@stomp/stompjs`) — pas de protocole maison à maintenir |
| **Broker simple en mémoire** | Une instance suffit pour un jeu entre amis ; passage à RabbitMQ possible sans changer le client |
| **PostgreSQL** | Robustesse, types JSON natifs pour les états/données de cartes, disponible partout |
| **Pinia** | État client typé et testable, stores séparés (`connection`, puis `game`, `deck`) |
| **TailwindCSS v4** | Thème du jeu déclaré dans le CSS (`@theme`), zéro fichier de configuration JS, build rapide |
| **GSAP** | Animations de plateau fluides (déploiement d'unités, combat, vol de Gigs) sans écrire de CSS d'animation à la main |
| **Cartes JSON** | Le contenu évolue sans livrer de code ; le schéma unique évite les divergences entre les trois langages |

## 6. Conventions

- **Backend** : paquets par responsabilité (`api`, `config`, `domain`, `engine`, `repository`, `service`),
  DTO en `record`, entités Lombok (`@Getter`/`@Setter`/`@Builder`), jamais d'entité JPA exposée telle quelle dans un DTO.
- **Frontend** : `<script setup lang="ts">`, alias `@/`, un store par domaine, composants de présentation sans appel réseau.
- **Tests** : le moteur de règles (`engine`) doit être couvert à ~100 % (tests unitaires purs) ; les contrôleurs se
  contentent de tests de fumée. Backend : H2 en mémoire, aucun Docker requis pour `mvn test`.
- **Git** : une branche par feature, commits conventionnels, PR relue avant merge (voir `roadmap.md`).

## 7. Montée en charge et évolutions prévues

1. **Broker externe** (`/topic` relayé par RabbitMQ) si plusieurs instances backend.
2. **Flyway** pour des migrations de schéma maîtrisées à la place de `hibernate.ddl-auto=update`.
3. **Spectateurs** : ajouter un rôle passif abonné au même `/topic/game.{id}`.
4. **Mode 3-4 joueurs** : le moteur est déjà séparé, seule la sélection de cible change.
5. **Historique et rejeu** : journaliser les `GameAction` + la graine RNG, puis rejouer côté serveur.
