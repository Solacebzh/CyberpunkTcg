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

Au premier lancement, le service peut démarrer même si PostgreSQL n'est pas là :
`GET /api/health` renverra alors `"database": "DOWN"`.

## Points d'entrée actuels

| Type | Endpoint / destination | Rôle |
| --- | --- | --- |
| REST | `GET /api/health` | Santé du service + état de la base |
| WS | `ws://localhost:8080/ws` | Handshake WebSocket (STOMP) |
| STOMP | `SEND /app/ping` → `SUBSCRIBE /topic/pong` | Test aller-retour temps réel |

## Tests

```bash
mvn test      # profil « test » : H2 en mémoire, aucun Docker requis
```

## Organisation du code (cible)

```
com.cyberpunktcg
├── api/            # contrôleurs REST + STOMP, DTO (records)
├── config/         # WebSocket, CORS, sérialisation
├── domain/         # modèle de jeu : Card, Game, GameState, Player, Deck (feature 02+)
├── engine/         # moteur de règles : phases, combat, résolution d'effets (feature 05+)
├── repository/     # Spring Data JPA
└── service/        # orchestration, transactions, diffusion temps réel
```

Détails : [`../docs/architecture.md`](../docs/architecture.md).
