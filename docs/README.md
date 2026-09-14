# Cyberpunk TCG Online — documentation

Ce dossier décrit **l'architecture globale** du projet et **comment le lancer en local**.
Les autres documents détaillent chaque aspect.

| Document | À lire quand… |
| --- | --- |
| **[architecture.md](architecture.md)** | tu veux comprendre les couches, le modèle « serveur autoritaire » et le flux d'une action |
| **[getting-started.md](getting-started.md)** | tu installes le projet sur une nouvelle machine |
| **[data-model.md](data-model.md)** | tu touches aux cartes (schéma JSON, entités JPA, tables PostgreSQL) |
| **[game-rules.md](game-rules.md)** | tu implémentes des règles ou du moteur de jeu |
| **[websocket-protocol.md](websocket-protocol.md)** | tu ajoutes un message temps réel (client **et** serveur) |
| **[roadmap.md](roadmap.md)** | tu veux savoir quoi faire ensuite / ce qui est déjà fait |

---

## 1. Vision en une image

```
┌──────────────────────────────┐        ┌────────────────────────────────────────┐
│        NAVIGATEUR            │        │        SERVEUR (autoritaire)           │
│                              │        │                                        │
│  Vue 3 + Pinia + Tailwind    │  REST  │  Spring Boot 3 / Java 21               │
│  ┌────────────────────────┐  │ ─────► │  ┌──────────────────────────────────┐  │
│  │ vues (Home, Lobby,     │  │        │  │ api/     contrôleurs REST + STOMP│  │
│  │  Table, DeckBuilder)   │  │  STOMP │  ├──────────────────────────────────┤  │
│  ├────────────────────────┤  │ ◄────► │  │ engine/  moteur de règles        │  │
│  │ stores Pinia           │  │  /ws   │  │          (phases, combat, Gigs)  │  │
│  │  • connection          │  │        │  ├──────────────────────────────────┤  │
│  │  • game (état diffusé) │  │        │  │ domain/  modèle de jeu           │  │
│  ├────────────────────────┤  │        │  ├──────────────────────────────────┤  │
│  │ services               │  │        │  │ service/ orchestration + diffusion│  │
│  │  • api.ts  (REST)      │  │        │  ├──────────────────────────────────┤  │
│  │  • socket.ts (STOMP)   │  │        │  │ repository/  Spring Data JPA     │  │
│  └────────────────────────┘  │        │  └──────────────────────────────────┘  │
└──────────────────────────────┘        │                 │                      │
                                        │                 ▼                      │
   Aucune règle de jeu côté client      │        ┌──────────────────┐            │
   → impossible de tricher              │        │   PostgreSQL 16  │            │
                                        │        └──────────────────┘            │
                                        └────────────────────────────────────────┘
                                                          ▲
                                       ┌──────────────────┴───────────────────┐
                                       │  scraper (Python)                    │
                                       │  cyberpunktcg.com → cards.json ──────┘
                                       │  (JSON conforme à card.schema.json)
                                       └──────────────────────────────────────┘
```

## 2. Le principe non négociable : serveur autoritaire

Le client **ne décide rien**. Il envoie des intentions (« je joue la carte X en main », « j'attaque avec l'unité Y »)
et affiche l'état que le serveur lui renvoie. Toute validation (règles, coûts, RAM, tours, cibles légales)
se fait côté serveur. Conséquences pratiques :

- le client peut être modifié/rafraîchi : il ne peut pas tricher ;
- l'état de partie est sérialisable et rejouable (utile pour les tests et le débogage) ;
- le frontend reste « bête » et donc rapide à faire évoluer.

## 3. Cartes data-driven

Une carte est **une donnée**, pas une classe Java ni un composant Vue :

```
cyberpunktcg.com ──scraper──► cards.json ──import──► PostgreSQL (table cards)
                                    │                        │
                                    │                        ├─► API REST   GET /api/cards
                                    └─ card.schema.json      └─► état de jeu STOMP /topic/game.{id}
                                       (contrat unique)
```

Le même schéma est décliné en trois langages, chacun avec son fichier de référence :

| Langage | Fichier | Rôle |
| --- | --- | --- |
| JSON Schema | [`schemas/card.schema.json`](schemas/card.schema.json) | **source de vérité** |
| Python | `scraper/src/cyberpunk_scraper/models.py` | validation à l'extraction/export |
| TypeScript | `frontend/src/types/card.ts` | typage de l'affichage |
| Java | `backend/.../domain/Card.java` (feature 02) | entité JPA |

Ajouter une carte ne demande donc **aucun déploiement de code** : on relance le scraper, on réimporte.

## 4. Architecture des dossiers

```
CyberpunkTcg/
├── backend/          Spring Boot : api / config / domain / engine / repository / service
├── frontend/         Vue 3 : components / views / stores / services / types
├── scraper/          Python : fetch → parse → models → export
├── docs/             cette documentation
├── docker-compose.yml  PostgreSQL (+ Adminer en option)
└── Makefile          raccourcis : make help
```

## 5. Lancer le projet en local

### Prérequis

| Outil | Version | Vérification |
| --- | --- | --- |
| Java JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js | 20.19+ ou 22.12+ | `node -v` (voir `.nvmrc`) |
| Python | 3.11+ | `python3 --version` |
| Docker + Compose | récents | `docker compose version` |

### Démarrage (3 terminaux)

```bash
# Terminal 1 — base de données PostgreSQL (port 5432)
docker compose up -d db

# Terminal 2 — backend Spring Boot (port 8080)
cd backend
mvn spring-boot:run

# Terminal 3 — frontend Vue (port 5173)
cd frontend
npm install
npm run dev
```

Puis : **http://localhost:5173**

Sur la page d'accueil, deux pastilles confirment que tout est branché :

- **API en ligne** → `GET /api/health` a répondu (le survol indique l'état de PostgreSQL) ;
- **WebSocket actif** → cliquer dessus ouvre la connexion STOMP, puis « Envoyer /app/ping » déclenche
  un aller-retour serveur visible dans le *journal temps réel*.

### Raccourcis utiles (Makefile)

```bash
make help           # liste toutes les cibles
make install        # dépendances backend + frontend + scraper
make db-up          # PostgreSQL
make backend        # Spring Boot
make frontend       # Vite
make test           # tests backend (H2) + scraper (pytest)
make build          # jar backend + bundle frontend
```

### Variables d'environnement

`cp .env.example .env` puis adapter. Valeurs par défaut : PostgreSQL `cyberpunk/cyberpunk@localhost:5432/cyberpunk_tcg`,
backend sur `8080`, frontend sur `5173`, origines CORS limitées à `localhost:5173`.

Le frontend utilise des **URLs relatives** (`/api`, `/ws`) : le proxy Vite les redirige vers le backend,
donc aucun CORS n'est nécessaire en développement et le jeu fonctionne aussi via un accès distant.

### Scraper (optionnel pour démarrer)

```bash
cd scraper
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/pip install -e .
.venv/bin/python -m cyberpunk_scraper cards --source fixtures   # 8 cartes d'exemple → output/cards.json
```

### Dépannage express

| Symptôme | Cause probable / solution |
| --- | --- |
| `API hors ligne` dans le frontend | backend arrêté, ou port 8080 occupé (`lsof -i :8080`) |
| `Base PostgreSQL : injoignable` mais l'API répond | `docker compose up -d db` puis revérifier |
| `Port 5173 is in use` | Vite choisit automatiquement un autre port : lire la sortie console |
| `WebSocket en erreur` | backend non démarré ; vérifier `ws://localhost:8080/ws` dans l'onglet réseau |
| `mvn: command not found` | installer Maven 3.9+ (ou utiliser le wrapper une fois ajouté) |
| Tests backend en échec | lancer `mvn test` : le profil `test` utilise H2, aucun Docker n'est requis |

Détails complets et procédure pas-à-pas : **[getting-started.md](getting-started.md)**.
