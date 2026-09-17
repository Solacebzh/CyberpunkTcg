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
Il gère aussi la présence comme le backend : fermer un onglet libère son siège et
signale `PLAYER_DISCONNECTED` à l'autre joueur.
**Ce n'est pas le moteur de règles** — la référence reste le backend Spring.

## Scripts

| Script | Rôle |
| --- | --- |
| `npm run dev` | Serveur de développement (HMR) |
| `npm run build` | Vérification des types (`vue-tsc`) puis build de production dans `dist/` |
| `npm run preview` | Sert le build de production localement |
| `npm run typecheck` | Vérification TypeScript seule |
| `npm run mock:ws` | Backend simulé (REST + STOMP) sur le port 8080 |
| `npm run test:unit` | Tests Vitest (flux lobby → partie, deck builder, reconnexion, présence) |
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
- La disposition du plateau est celle du **tapis officiel** : une seule grille CSS
  (`.playmat-grid` dans `src/assets/main.css`, zones `data-zone="FIXER|FIELD|DECK|LEGENDS|
  EDDIES|TRASH"`, compteurs de Gigs en haut). Détails et mapping serveur →
  [`../docs/FRONTEND-ARCHITECTURE.md`](../docs/FRONTEND-ARCHITECTURE.md) § 6 bis.
- Une seule connexion STOMP par page, partagée via `useGameSocket()`.

## Deck builder (`/deck`)

Quatre actions de contexte, toujours visibles :

| Bouton | Effet |
| --- | --- |
| **+ Nouveau Deck** | repart d'une liste **vide** avec le nom par défaut `Nouveau deck` (`deckStore.resetDeck()`) ; détache aussi l'éditeur du deck sauvegardé chargé |
| **Importer un Deck** | modale d'import texte (remplace la liste, détache le deck sauvegardé) |
| **Deck d'exemple** | deck légal généré depuis le catalogue |
| **Vider le deck** | retire les cartes **sans** détacher le deck chargé (mis à jour par « Sauvegarder ») |

Format accepté par l'import, une carte par ligne :

```
// Legends (3)
1 Adam Smasher: Metal Over Meat
1 Johnny Silverhand - Rocking Renegade
1 Royce: Psycho on the Edge

// Main deck (40)
3 The Heist
```

- quantité en tête (`3`, `3x`, `3 X`), commentaires `//` et `#` ignorés ;
- le séparateur entre le nom et le sous-titre est au choix `:`, ` - `, `|` ou des
  parenthèses — les cartes homonymes du catalogue (deux « Adam Smasher », trois
  « V », « Goro Takemura », …) sont résolues **par le sous-titre** ;
- une ligne sans sous-titre qui colle à plusieurs versions est importée sur la
  première version du catalogue et **signalée en jaune** dans la modale ;
- un sous-titre qui ne correspond à aucune version rend la ligne « non reconnue » :
  rien n'est deviné.

## Jouer une partie en local

1. `npm run mock:ws` + `npm run dev` (ou le backend Spring + `npm run dev`) ;
2. onglet 1 : `/lobby` → pseudo → **Créer le salon** → noter le code à 6 caractères ;
3. onglet 2 : `/lobby` → autre pseudo → **Rejoindre** avec le code ;
4. la partie démarre automatiquement : vendre une carte rapporte le premier Eddie,
   puis poser une Unit, attaquer, voler des Gigs (objectif 7).
