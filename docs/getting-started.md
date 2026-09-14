# Prise en main

Ce document part du principe que tu viens de cloner le dépôt sur une machine vierge.

## 1. Prérequis

| Outil | Version | Installation rapide | Vérification |
| --- | --- | --- | --- |
| Java JDK | **21** | `sdk install java 21.0.5-tem` (SDKMAN) ou Temurin/Adoptium | `java -version` |
| Maven | **3.9+** | `sdk install maven` ou paquet système | `mvn -version` |
| Node.js | **20.19+ ou 22.12+** | `nvm use` (le fichier `.nvmrc` est fourni) | `node -v` |
| Python | **3.11+** | paquet système | `python3 --version` |
| Docker + Compose | version récente | Docker Desktop / `docker.io` | `docker compose version` |

> Workflow Git : une branche par sujet (`feature/XX-nom`), commits `feat:`/`fix:`/`docs:`/`chore:`,
> PR relue avant merge. Aucun commit direct sur `main`.

## 2. Installation

```bash
git clone <url-du-depot> CyberpunkTcg
cd CyberpunkTcg

cp .env.example .env          # valeurs par défaut : conviennent pour un poste local

# Dépendances (ou : make install)
docker compose up -d db       # PostgreSQL 16 sur le port 5432
cd backend  && mvn -B dependency:go-offline && cd ..
cd frontend && npm install && cd ..
```

Optionnel (uniquement pour travailler sur les cartes) :

```bash
cd scraper
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install -e .     # enregistre le paquet (CLI `cyberpunk-scraper`)
```

## 3. Lancer les services

### Terminal 1 — base de données

```bash
docker compose up -d db          # démarrage
docker compose logs -f db        # journal
docker compose down              # arrêt (les données restent dans le volume db-data)
docker compose down -v           # arrêt + suppression des données
```

Interface d'administration optionnelle : `docker compose --profile tools up -d` → http://localhost:8081
(serveur `db`, utilisateur/mot de passe `cyberpunk`/`cyberpunk`).

### Terminal 2 — backend (port 8080)

```bash
cd backend
mvn spring-boot:run
```

Vérifications :

```bash
curl -s http://localhost:8080/api/health | python3 -m json.tool
# {
#   "status": "UP",
#   "service": "cyberpunk-tcg-backend",
#   "version": "0.1.0-SNAPSHOT",
#   "database": "UP",
#   "timestamp": "…Z"
# }
```

`"database": "DOWN"` signifie que l'API tourne mais que PostgreSQL n'est pas joignable :
lancer `docker compose up -d db`.

### Terminal 3 — frontend (port 5173)

```bash
cd frontend
npm run dev
```

Ouvrir http://localhost:5173. La page d'accueil affiche :

- la pastille **API en ligne** (issue de `GET /api/health`) ;
- la pastille **WebSocket actif** — un clic ouvre le canal STOMP, puis « Envoyer /app/ping »
  déclenche un aller-retour visible dans le *journal temps réel*.

## 4. Variables d'environnement

Toutes les valeurs ont un défaut fonctionnel en local ; `.env` n'est donc pas obligatoire.

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `cyberpunk_tcg` / `cyberpunk` / `cyberpunk` | Base créée par Docker |
| `POSTGRES_PORT` | `5432` | Port exposé par PostgreSQL |
| `SERVER_PORT` | `8080` | Port du backend |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/cyberpunk_tcg` … | Connexion JDBC |
| `JPA_DDL_AUTO` | `update` | `update` en dev, `validate` en production (migrations) |
| `JPA_SHOW_SQL` | `false` | Journaliser le SQL généré |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Origines autorisées (REST **et** WebSocket) |
| `LOG_LEVEL_APP` | `DEBUG` | Niveau de log du paquet `com.cyberpunktcg` |
| `VITE_API_BASE_URL` | *(vide)* | Base d'API côté client ; vide = URLs relatives `/api` (recommandé) |
| `VITE_WS_URL` | *(vide)* | URL absolue du WebSocket ; vide = `ws(s)://<origine>/ws` |
| `VITE_DEV_API_TARGET` | `http://localhost:8080` | Cible du proxy Vite (dev uniquement) |

## 5. Tests et builds

```bash
# Backend : profil « test » → H2 en mémoire, aucun Docker requis
cd backend && mvn test

# Frontend : vérification des types puis build de production
cd frontend && npm run build

# Scraper : normalisation, garde-fous de règles, export, validation du schéma
cd scraper && .venv/bin/python -m pytest

# Tout d'un coup, depuis la racine
make test
make build
```

## 6. Dépannage

| Symptôme | Diagnostic | Solution |
| --- | --- | --- |
| `API hors ligne` | `curl localhost:8080/api/health` échoue | démarrer le backend ; vérifier le port avec `lsof -i :8080` |
| `database: DOWN` | PostgreSQL absent | `docker compose up -d db` |
| `relation "…" does not exist` | schéma non synchronisé | en dev, `JPA_DDL_AUTO=update` doit créer les tables ; sinon `make db-reset` |
| `Port 5173 is in use` | autre instance de Vite | lire le port réel dans la console, ou `npm run dev -- --port 5174` |
| `WebSocket en erreur` | backend arrêté ou proxy inactif | démarrer le backend ; tester `ws://localhost:8080/ws` (onglet Réseau → WS) |
| Erreur CORS | origine non autorisée | ajouter l'origine dans `CORS_ALLOWED_ORIGINS` puis redémarrer le backend |
| `mvn: command not found` | Maven absent | installer Maven 3.9+ |
| Tests Python : `No module named cyberpunk_scraper` | paquet non installé | `.venv/bin/pip install -e .` dans `scraper/` |
| `POSTGRES_PASSWORD` dans les logs | configuration oubliée | copier `.env.example` vers `.env` et adapter |

## 7. Accès depuis l'extérieur (jouer avec des amis)

En local, tout passe par des URLs relatives et le proxy Vite. Pour une partie à distance, deux options :

1. **Un seul port exposé** : construire le frontend (`npm run build`) et le servir derrière le même
   domaine que l'API (reverse proxy Nginx/Caddy → `:8080` pour `/api` et `/ws`, fichiers statiques sinon).
   C'est la configuration cible, elle évite tout problème de CORS et de WebSocket cross-origin.
2. **Deux domaines** : renseigner `VITE_API_BASE_URL` / `VITE_WS_URL` côté frontend et ajouter l'origine
   du frontend dans `CORS_ALLOWED_ORIGINS` côté backend.

⚠️ Ne jamais laisser `CORS_ALLOWED_ORIGINS=*` avec `allowCredentials=true` : la configuration actuelle
refuse d'ailleurs ce cas de figure.
