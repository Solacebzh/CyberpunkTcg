# backend — Serveur autoritaire (Spring Boot 3 / Java 21)

Cœur du jeu : le serveur détient l'état des parties et valide toutes les actions.
Le client n'envoie que des **intentions** et reçoit des **états**.

## Démarrage

```bash
# PostgreSQL (depuis la racine du dépôt)
docker compose up -d db

# Lancement en dev (web server sur :8080)
cd backend
mvn spring-boot:run
```

Le profil local requiert PostgreSQL pour créer le schéma et importer le catalogue. Le profil `test` utilise
H2 en mémoire et ne requiert ni Docker ni service externe.

## Points d'entrée actuels

| Type | Endpoint / destination | Rôle |
| --- | --- | --- |
| REST | `GET /api/health` | Santé du service + état de la base |
| REST | `GET /api/cards?type=unit&color=red` | Catalogue, filtres facultatifs type/couleur |
| REST | `GET /api/cards/{id}` | Détail d'une carte (`404` si absente) |
| REST | `GET /api/cards/stats` | Totaux par type et couleur |
| REST | `GET /api/decks` | **Mes decks** (JWT requis) — uniquement ceux du compte courant |
| REST | `POST /api/decks` | Sauvegarde d'un deck (`201`) ; `400` + liste des infractions si les règles officielles sont violées |
| REST | `PUT /api/decks/{id}` | Mise à jour d'un de mes decks (re-validation complète) |
| REST | `DELETE /api/decks/{id}` | Suppression d'un de mes decks (`204`) |
| WS | `ws://localhost:8080/ws` | Handshake WebSocket (STOMP) |
| STOMP | `SEND /app/ping` → `SUBSCRIBE /topic/pong` | Test aller-retour temps réel |

Au démarrage, `CardDataInitializer` importe `src/main/resources/data/cards.json` uniquement si la table
`cards` est vide. Le contrat machine est `src/main/resources/schema/card-schema.json`.

## Tests

```bash
mvn test      # profil « test » : H2 en mémoire, import des 5 cartes, aucun Docker requis
```

## Organisation du code (cible)

```
com.cyberpunktcg
├── api/            # contrôleurs REST + STOMP, DTO (records)
├── config/         # WebSocket, CORS, sérialisation
├── domain/         # modèle de jeu : Card, Game, GameState, Player, User, Deck (deck = compte + règles)
├── engine/         # moteur de règles : phases, combat, résolution d'effets (feature 05+)
├── repository/     # Spring Data JPA
└── service/        # orchestration, transactions, diffusion temps réel
```

Détails : [`../docs/architecture.md`](../docs/architecture.md).
