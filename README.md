# Cyberpunk TCG Online

Jeu de cartes à collectionner **Cyberpunk TCG** (WeirdCo) jouable en ligne entre amis, dans le navigateur.
Serveur **autoritaire** (toute la logique de jeu vit côté serveur), cartes **data-driven** (JSON → base → client).

> Inspiré des règles officielles du Cyberpunk TCG : 3 Legends + 40-50 cartes, ressource *Eddies*,
> *Gigs* en dés, *Street Cred*, *RAM* par couleur (Rouge / Vert / Bleu / Jaune), victoire à **7 Gigs**.
> Voir [`docs/game-rules.md`](docs/game-rules.md).

---

## Stack technique

| Couche | Technologies |
| --- | --- |
| Backend | Java 21 · Spring Boot 3.5 · Spring WebSocket (STOMP) · Spring Data JPA · PostgreSQL 16 · Lombok |
| Frontend | Vue 3 (Composition API + TypeScript) · Pinia · Vue Router · TailwindCSS · GSAP · @stomp/stompjs · Vite |
| Données | Cartes du jeu scrapées puis normalisées en JSON (`card-schema.json`) et importées en base |
| Scraper | Python 3.11+ (httpx · Pydantic · JSON Schema), API NetDeck paginée + cache |
| Outillage | Docker Compose (PostgreSQL) · Makefile |

## Structure du monorepo

```
CyberpunkTcg/                  # racine du monorepo (= racine du dépôt git)
├── backend/                   # Serveur autoritaire — Spring Boot 3 (API REST + STOMP)
│   ├── pom.xml
│   ├── src/main/resources/data/cards.json
│   ├── src/main/resources/schema/card-schema.json
│   └── src/{main,test}/...
├── frontend/                  # Client web — Vue 3 + Vite + Tailwind
│   ├── package.json
│   ├── vite.config.ts
│   └── src/...
├── scraper/                   # Récupération/normalisation des cartes — Python
│   ├── requirements.txt
│   └── src/cyberpunk_scraper/...
├── docs/                      # Documentation du projet (architecture, règles, protocole…)
│   ├── README.md              # 👉 point d'entrée : architecture + lancement local
│   ├── architecture.md
│   ├── getting-started.md
│   ├── DATA-MODEL.md
│   ├── game-rules.md
│   ├── RULE-ENGINE.md
│   ├── WEBSOCKET-PROTOCOL.md
│   ├── FRONTEND-ARCHITECTURE.md
│   └── roadmap.md
├── docker-compose.yml         # PostgreSQL local
├── Makefile                   # Raccourcis de développement
└── README.md
```

## Démarrage rapide

```bash
# 1. Base de données PostgreSQL
docker compose up -d db

# 2. Backend (http://localhost:8080)
cd backend && mvn spring-boot:run

# 3. Frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

Checklist de validation en 30 secondes :

- `curl http://localhost:8080/api/health` → `{"status":"UP", ...}`
- `curl http://localhost:8080/api/cards` → catalogue JSON importé par JPA
- `http://localhost:5173` → page d'accueil, badge **API** vert et badge **WebSocket : pong** allumé
  (le bouton de test ouvre une vraie connexion STOMP sur `/ws`).

📖 Détails, variables d'environnement et dépannage : [`docs/getting-started.md`](docs/getting-started.md).

## Documentation

| Document | Contenu |
| --- | --- |
| [docs/README.md](docs/README.md) | Architecture globale + lancement local (vue d'ensemble) |
| [docs/architecture.md](docs/architecture.md) | Architecture détaillée, modèle serveur autoritaire, couches |
| [docs/getting-started.md](docs/getting-started.md) | Prérequis, installation, variables d'env, dépannage |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | Schéma JSON des cartes, modèle de données PostgreSQL |
| [docs/game-rules.md](docs/game-rules.md) | Règles du jeu résumées et périmètre du moteur |
| [docs/WEBSOCKET-PROTOCOL.md](docs/WEBSOCKET-PROTOCOL.md) | Contrat STOMP (destinations, enveloppes, synchronisation) |
| [docs/FRONTEND-ARCHITECTURE.md](docs/FRONTEND-ARCHITECTURE.md) | Client Vue : composants, stores, flux de données, animations |
| [docs/roadmap.md](docs/roadmap.md) | Découpage en features et état d'avancement |

## Workflow Git du projet

1. Une branche par sujet : `feature/XX-nom-de-la-tache`, créée depuis `main`.
2. Commits conventionnels : `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`.
3. Une Pull Request par feature, avec description, liste des fichiers, instructions de test et dépendances ajoutées.
4. **Aucun merge automatique** : la PR attend une relecture/approbation humaine, puis le sujet suivant repart de `main` à jour.

## État du projet

| Feature | Contenu | Statut |
| --- | --- | --- |
| 01 | Setup du monorepo (backend, frontend, scraper, docs, Docker) | ✅ livré |
| 02 | Scraping paginé + schéma + import JPA + API `/api/cards` | 🟡 PR en revue |
| 03 | Comptes joueurs, deck building, validation des decks | ⏳ à venir |
| 04 | Lobby temps réel et partie 1v1 (serveur autoritaire) | ⏳ à venir |
| 05 | Moteur de règles complet (phases, combat, Gig, React window) | ⏳ à venir |
| 06 | UI de jeu (plateau, animations GSAP, journal de partie) | ⏳ à venir |

Détail complet : [`docs/roadmap.md`](docs/roadmap.md).

---

*Projet privé entre amis. Cyberpunk® est une marque de CD PROJEKT RED ; Cyberpunk TCG est édité par WeirdCo.
Ce projet est un outil de jeu non commercial et n'est affilié à aucun de ces éditeurs.*
