# frontend — Client web (Vue 3 + TypeScript + Vite)

Client du jeu : rendu du plateau, deck builder et lobby. **Aucune règle de jeu ici** —
le frontend ne fait qu'envoyer des intentions (`/app/...`) et afficher l'état diffusé par le serveur (`/topic/...`).

## Démarrage

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

Le backend doit tourner sur `http://localhost:8080` (voir `../backend/README.md`) :
Vite proxifie `/api` et `/ws` vers cette cible, donc **aucun CORS ni URL absolue n'est nécessaire** en dev.
La page d'accueil charge réellement `GET /api/cards`; elle affiche un état d'erreur et permet de réessayer si
le backend n'est pas disponible.

## Scripts

| Script | Rôle |
| --- | --- |
| `npm run dev` | Serveur de développement (HMR) |
| `npm run build` | Vérification des types (`vue-tsc`) puis build de production dans `dist/` |
| `npm run preview` | Sert le build de production localement |
| `npm run typecheck` | Vérification TypeScript seule |

## Organisation

```
src/
├── assets/main.css        # Tailwind v4 : thème via @theme + composants maison
├── components/            # CardPreview, ConnectionBadge… (présentation pure)
├── data/                  # Jeux de données d'exemple (JSON) pour le rendu data-driven
├── router/                # Routes (vue-router)
├── services/              # api.ts (REST), socket.ts (STOMP)
├── stores/                # Pinia (connection, puis game/deck)
├── types/                 # Types partagés, miroir du schéma de carte
└── views/                 # Écrans (HomeView, LobbyView…)
```

## Conventions

- **Composition API + `<script setup lang="ts">`** uniquement.
- Alias `@/` → `src/`.
- `strict: true` côté TypeScript ; `npm run build` échoue si les types ne sont pas propres.
- Les classes Tailwind dynamiques doivent être écrites en toutes lettres (le scanner ne lit pas les chaînes construites à l'exécution).
