# Modèle de données

## 1. Du site au client

```
cyberpunktcg.com
      │  scraper (Python) : extraction → normalisation → validation
      ▼
cards.json      (conforme à schemas/card.schema.json)
      │  import backend (feature 02) : POST /api/cards/import
      ▼
PostgreSQL.table cards     ←→  entité JPA Card
      │  GET /api/cards
      ▼
frontend : types/card.ts → components/CardPreview.vue
```

**Un seul contrat** : [`schemas/card.schema.json`](schemas/card.schema.json), décliné en trois langages
(voir `docs/README.md` §3). Toute évolution du schéma se fait dans ce fichier d'abord, puis dans les trois implémentations.

## 2. Schéma d'une carte

```json
{
  "id": "wtnc-u112-night-city-solo-gun-for-hire",
  "name": "Night City Solo",
  "subtitle": "Gun for Hire",
  "type": "unit",
  "color": "red",
  "ram": 3,
  "cost": 4,
  "power": 5,
  "streetCred": 1,
  "tags": ["MERCS", "SOLO"],
  "keywords": ["go_solo"],
  "text": "Go Solo. Quand cette Unité attaque, volez 1 Gig si sa Puissance dépasse celle du défenseur.",
  "flavorText": null,
  "setCode": "WTNC",
  "collectorNumber": "U112",
  "rarity": "rare",
  "imageUrl": null
}
```

| Champ | Type | Notes |
| --- | --- | --- |
| `id` | string (slug) | identifiant stable : `set-numéro-nom[-sous-titre]`, généré par le scraper |
| `name` / `subtitle` | string / string? | nom affiché et épithète |
| `type` | `legend｜unit｜program｜gear` | seules les Units ont une Puissance |
| `color` | `red｜green｜blue｜yellow` | système « Color Tree » |
| `ram` | int 0-6 | plafonné par la somme des RAM des Legends du deck |
| `cost` | int? | coût en Eddies — `null` pour une Legend |
| `power` | int? | Puissance de combat — Units uniquement |
| `streetCred` | int? | seuil de Street Cred requis |
| `tags` | string[] | en majuscules, dédoublonnés (`ARASAKA`, `MERCS`…) |
| `keywords` | enum[] | `go_solo`, `blocker`, `quick`, `flip`, `play`, `attack` |
| `text` | string | texte de règle |
| `setCode` / `collectorNumber` | string | `WTNC` / `A029` |
| `rarity` | enum? | `common`, `uncommon`, `rare`, `legendary` |
| `imageUrl` | string? | illustration (à recopier dans notre stockage à terme, pour ne pas dépendre d'un CDN tiers) |

Le schéma est volontairement **strict** (`additionalProperties: false`) : un champ inventé fait échouer
la validation côté scraper *et* côté import. C'est ce qui garantit que les trois langages restent alignés.

### Garde-fous de règles (appliqués par le scraper et l'import)

- une **Unit** doit avoir une Puissance ; les autres types ne doivent pas en avoir ;
- une **Legend** n'a pas de coût en Eddies (elle est posée face cachée puis retournée) ;
- `ram` entre 0 et 6, couleurs/types limités aux valeurs officielles ;
- `id` unique : un doublon est signalé dans `manifest.json` et rejeté à l'import.

## 3. États d'export du scraper

| Fichier | Contenu |
| --- | --- |
| `cards.json` | tableau de cartes conforme au schéma (entrée de l'import) |
| `cards.jsonl` | une carte par ligne (import en flux, revue diff) |
| `manifest.json` | compte, statistiques par type/couleur, SHA-256 des fichiers, carte rejetées et raisons, date |
| `provenance.json` | origine de chaque carte (URL, date de récupération) — séparé pour garder `cards.json` strictement conforme |

## 4. Modèle relationnel prévu (features 02-04)

```
players                       cards                        decks
─────────                     ─────────                    ─────────
id            uuid PK         id            text PK        id           uuid PK
display_name  text            name          text           owner_id     uuid FK → players
email         text unique     type          text           name         text
password_hash text            color         text           legend_ids   text[]   (3 Legends)
created_at    timestamptz     ram           smallint       created_at   timestamptz
                              cost          smallint
                              power         smallint       deck_cards
                              street_cred   smallint       ──────────
                              tags          text[]         deck_id      uuid FK → decks
                              keywords      text[]         card_id      text FK → cards
                              text          text           quantity     smallint (≤ 3)
                              set_code      text
                              collector_no  text           games
                              rarity        text           ─────
                              image_url     text           id            uuid PK
                              payload       jsonb          player1_id    uuid FK
                              ─────                          player2_id    uuid FK
                              index (set_code,             winner_id     uuid FK
                                     collector_no)           result        text
                                                           started_at    timestamptz
                                                           finished_at   timestamptz
                                                           seed          bigint  (rejeu déterministe)
                                                           log           jsonb   (actions + états)
```

Notes de conception :

- `cards.payload` (jsonb) conserve la carte d'origine telle qu'extraite : si le schéma évolue, on peut
  rejouer l'import sans relancer le scraping ;
- l'**état d'une partie en cours vit en mémoire** (feature 04/05) : la base ne reçoit la partie qu'à la fin
  (`games`), ce qui évite des écritures à chaque action pour un jeu entre amis ;
- les migrations seront gérées par **Flyway** dès qu'elles deviendront plus lourdes que
  `hibernate.ddl-auto=update` (voir la roadmap, feature 03) ;
- la **validation de deck** (`R1`) est calculée à partir des cartes réellement présentes en base —
  c'est le serveur qui décide, jamais le deck builder du client.

## 5. État de jeu diffusé (aperçu, détaillé en feature 05)

```jsonc
{
  "gameId": "8f2c…",
  "sequence": 42,
  "turn": { "number": 7, "playerId": "b1…", "phase": "play" },
  "me":    { "playerId": "a0…", "eddies": 3, "streetCred": 6, "gigs": 2,
             "hand": [ /* cartes complètes */ ], "deckCount": 31,
             "field": [ { "instanceId": "i-17", "cardId": "wtnc-u112…", "spent": false,
                          "power": 7, "attachments": [ "i-18" ] } ],
             "legends": [ { "instanceId": "l-1", "cardId": "wtnc-a029…", "faceUp": false } ] },
  "rival": { "playerId": "b1…", "eddies": 5, "streetCred": 2, "gigs": 1,
             "handCount": 4, "deckCount": 28,
             "field": [ /* même forme */ ], "legends": [ /* faceUp visible seulement */ ] },
  "log": [ { "at": "…", "text": "Night City Solo attaque Trauma Team Medic" } ]
}
```

Règle d'or : **l'état envoyé à un joueur ne contient jamais d'information qu'il ne doit pas voir**
(main adverse masquée, Legends face cachée masquées). Le filtrage est fait côté serveur, par destinataire.
