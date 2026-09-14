# frontend — Client web (Vue 3 + TypeScript + Vite)

Client du jeu : lobby, deck builder, plateau de jeu. **Aucune règle de jeu ici** —
le frontend ne fait qu'envoyer des intentions (`/app/...`) et afficher l'état
diffusé par le serveur (`/topic/...`).

📖 Architecture détaillée (composants, stores, flux, animations) :
[`../docs/FRONTEND-ARCHITECTURE.md`](../docs/FRONTEND-ARCHITECTURE.md).
Contrat de transport : [`../docs/WEBSOCKET-PROTOCOL.md`](../docs/WEBSOCKET-PROTOCOL.md).

## Démarrage

### Avec le backend Spring (référence)

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

Le backend doit tourner sur `http://localhost:8080` (voir `../backend/README.md`) :
Vite proxifie `/api` et `/ws` vers cette cible, donc **aucun CORS ni URL absolue
n'est nécessaire** en dev.

### Sans backend (travail sur l'UI)

```bash
npm run mock:ws    # API REST + STOMP simulés sur :8080 (catalogue réel du backend)
npm run dev        # dans un autre terminal
```

Le serveur simulé (`devtools/mock-protocol.ts`) reproduit le contrat STOMP et un
sous-ensemble de règles : suffisant pour jouer une partie complète à deux onglets.
**Ce n'est pas le moteur de règles** — la référence reste le backend Spring.

## Scripts

| Script | Rôle |
| --- | --- |
| `npm run dev` | Serveur de développement (HMR) |
| `npm run build` | Vérification des types (`vue-tsc`) puis build de production dans `dist/` |
| `npm run preview` | Sert le build de production localement |
| `npm run typecheck` | Vérification TypeScript seule |
| `npm run mock:ws` | Backend simulé (REST + STOMP) sur le port 8080 |
| `npm run test:unit` | Tests Vitest (flux lobby → partie, deck builder, reconnexion) |
| `npm run test:watch` | Les mêmes tests en mode watch |

## Organisation

```
src/
├── assets/main.css        # Tailwind v4 : thème cyberpunk (@theme) + composants maison
├── components/            # CardComponent, TargetingOverlay, game/*, ui/* (présentation)
├── composables/           # useGameSocket (canal STOMP), useGameAnimations (GSAP)
├── router/                # Routes : /, /lobby, /deck, /game/:gameId?
├── services/              # api.ts (REST), socket.ts (enveloppe @stomp/stompjs)
├── stores/                # Pinia : connection, deck, lobby, game, ui
├── types/                 # card.ts (schéma de carte), game.ts (DTO WebSocket)
├── views/                 # HomeView, LobbyView, DeckBuilderView, GameView
└── __tests__/             # Tests de flux + harnais (fausse WebSocket)

devtools/mock-protocol.ts  # Serveur STOMP simulé (contrat de transport)
tools/mock-server.mjs      # `npm run mock:ws` : /api/* + ws://…/ws
```

## Conventions

- **Composition API + `<script setup lang="ts">`** uniquement.
- Alias `@/` → `src/`.
- `strict: true` côté TypeScript ; `npm run build` échoue si les types ne sont pas propres.
- Les classes Tailwind dynamiques doivent être écrites en toutes lettres (le scanner ne lit pas les chaînes construites).
- Les attributs `data-instance-id` / `data-anim="…"` sont les points d'accroche des
  animations GSAP : ne pas les renommer sans mettre à jour `useGameAnimations`.
- Une seule connexion STOMP par page, partagée via `useGameSocket()`.

## Jouer une partie en local

1. `npm run mock:ws` + `npm run dev` (ou le backend Spring + `npm run dev`) ;
2. onglet 1 : `/lobby` → pseudo → **Créer le salon** → noter le code à 6 caractères ;
3. onglet 2 : `/lobby` → autre pseudo → **Rejoindre** avec le code ;
4. la partie démarre automatiquement : vendre une carte rapporte le premier Eddie,
   puis poser une Unit, attaquer, voler des Gigs (objectif 7).
