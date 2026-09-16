# Modèle de données des cartes

Ce document est le contrat de passage pour les prochaines features. La source de vérité machine est
[`backend/src/main/resources/schema/card-schema.json`](../backend/src/main/resources/schema/card-schema.json).
Le modèle Pydantic, les DTO/entités Java et le type TypeScript doivent rester alignés sur ce fichier.

## 1. Pipeline complet

```text
cyberpunktcg.com/cards
        │ utilise l'API publique NetDeck
        ▼
GET api.netdeck.gg/api/cards/cyberpunk?limit=100&offset=N
        │ HttpClient : cache disque + retries + délai entre requêtes
        ▼
scraper/site.py : pagination et mapping de la source
        │
scraper/parse.py + models.py : normalisation et validation Pydantic
        │
scraper/export.py : validation JSON Schema + cards.json / JSONL / manifest
        ▼
backend/src/main/resources/data/cards.json
        │ CardDataInitializer → CardService.importBundledCardsIfEmpty()
        ▼
PostgreSQL / H2 : cards + card_tags + card_keywords + card_abilities
        │ CardRepository → CardService → CardController
        ▼
GET /api/cards → frontend/services/api.ts → HomeView → CardPreview
```

L'import de démarrage est **idempotent** : il charge le JSON seulement lorsque la table `cards` est vide.
Une base déjà initialisée n'est ni vidée ni écrasée. Une entrée invalide ou un identifiant dupliqué fait
échouer explicitement le démarrage plutôt que de produire un catalogue partiel.

## 2. Schéma, champ par champ

Tous les champs ci-dessous sont présents dans le JSON. Les valeurs absentes de la source sont représentées
par `null` pour les scalaires optionnels et par `[]` pour les listes.

| Champ | Type JSON | Obligatoire | Stockage JPA | Description / normalisation |
| --- | --- | --- | --- | --- |
| `id` | string | oui | `cards.id` (PK) | Slug stable en kebab-case, issu de NetDeck (`v-streetkid`) ou généré depuis set/numéro/nom. |
| `name` | string | oui | `cards.name` | Nom principal imprimé. |
| `subtitle` | string \| null | oui | `cards.subtitle` | Épithète (`Streetkid`, `Stubborn Patriarch`), sans duplication du nom. |
| `type` | enum | oui | `cards.card_type` | `legend`, `unit`, `program` ou `gear`, toujours en minuscules dans l'API REST. |
| `color` | enum | oui | `cards.color` | `red`, `green`, `blue` ou `yellow`, toujours en minuscules. |
| `ram` | integer 0–20 | oui | `cards.ram` | RAM imprimée ; `0` si la source ne publie pas de valeur. |
| `cost` | integer \| null | oui | `cards.cost` | Coût en Eddies. Une Legend peut avoir un coût imprimé ; ne pas l'effacer. |
| `power` | integer \| null | oui | `cards.power` | Puissance imprimée. Elle peut exister sur une Unit, une Legend ou un Gear. |
| `streetCred` | integer \| null | oui | `cards.street_cred` | Seuil explicite de Street Cred lorsqu'il existe. |
| `tags` | string[] | oui | `card_tags` | Classifications NetDeck, nettoyées, en majuscules et dédupliquées. |
| `keywords` | enum[] | oui | `card_keywords` | `go_solo`, `blocker`, `quick`, `flip`, `play`, `attack`, détectés aussi dans le balisage `{Keyword}`. |
| `text` | string | oui | `cards.rules_text` | Texte de règles complet, espaces normalisés. |
| `abilities` | string[] | oui | `card_abilities` | Paragraphes d'effet dans l'ordre d'impression ; sert au rendu détaillé et au futur moteur. |
| `imageUrl` | URI \| null | oui | `cards.image_url` | `source_image_url` stable. Les signatures CloudFront temporaires sont retirées. |
| `setCode` | string | oui | `cards.set_code` | Code NetDeck normalisé en majuscules alphanumériques, 64 caractères maximum. |
| `collectorNumber` | string | oui | `cards.collector_number` | Numéro imprimé, chaîne pour conserver préfixes et variantes (`005a`, `β006`). |
| `rarity` | enum \| null | oui | `cards.rarity` | `common`, `uncommon`, `rare`, `epic`, `secret`, `iconic`, `nova`, `promo`. |

Le schéma interdit les propriétés inconnues (`additionalProperties: false`). Les bornes et formats sont
également vérifiés par Bean Validation pendant l'import backend.

## 3. Source et pagination du scraper

`cyberpunktcg.com` utilise l'API publique suivante pour sa base de cartes :

```text
https://api.netdeck.gg/api/cards/cyberpunk?limit=100&offset=0
```

Chaque réponse contient `total` et `items`. Le scraper avance `offset` du nombre d'items réellement reçus,
vérifie que `total` reste stable, refuse une page vide prématurée et rejette les slugs dupliqués. Le cache
disque est indexé par URL : relancer sans `--refresh` n'appelle pas le serveur pour les pages déjà connues.

```bash
cd scraper
.venv/bin/python -m cyberpunk_scraper cards --source site
.venv/bin/python -m cyberpunk_scraper validate output/cards.json
```

`manifest.json` contient le nombre de cartes, les statistiques, les rejets et les SHA-256. Les images ne sont
pas copiées dans Git : seules leurs URLs stables sont stockées.

## 4. Import et modèle relationnel

La ressource de développement et de test est
[`backend/src/main/resources/data/cards.json`](../backend/src/main/resources/data/cards.json) (5 cartes).
Au démarrage :

1. `CardDataInitializer` appelle `CardService` ;
2. `CardService` quitte sans modification si `CardRepository.count() > 0` ;
3. sinon Jackson lit toutes les entrées, Bean Validation les contrôle et les IDs sont dédupliqués ;
4. `CardRepository.saveAll()` écrit les cartes et leurs trois collections ordonnées.

```text
cards (id PK, name, subtitle, card_type, color, ram, cost, power,
       street_cred, rules_text, image_url, set_code, collector_number, rarity)
  ├── card_tags      (card_id FK, position, tag)
  ├── card_keywords  (card_id FK, position, keyword)
  └── card_abilities (card_id FK, position, ability)
```

Le profil `test` utilise H2 en mode PostgreSQL et `ddl-auto=create-drop`. Le profil local utilise PostgreSQL.

Depuis la **Mini-Feature 9C**, les comptes et leurs decks sont aussi créés par `ddl-auto` :

```text
users (id PK, username UNIQUE, password, created_at)
decks (id PK, name, user_id, created_at, updated_at)
  └── deck_cards (deck_id FK, position, card_id)   # ordre du deck + exemplaires (3 max)
```

`deck_cards.card_id` répète l'identifiant du catalogue **sans clé étrangère** vers `cards` :
la cohérence est vérifiée par `DeckService` à la sauvegarde (carte inconnue → `400`), ce qui
laisse un deck lisible même lorsque le catalogue évolue. `user_id` n'a pas non plus de
contrainte FK : un deck n'est jamais lu sans le filtre `WHERE user_id = ?` du joueur courant.
Ces deux tables devront être reprises telles quelles lors du passage à Flyway (feature 03).

## 5. API REST

| Méthode | Route | Résultat |
| --- | --- | --- |
| `GET` | `/api/cards` | Toutes les cartes triées par nom puis ID. |
| `GET` | `/api/cards?type=unit&color=red` | Filtres facultatifs combinables `type` / `color`; valeur inconnue → `400`. |
| `GET` | `/api/cards/{id}` | Une carte au contrat complet; ID inconnu → `404`. |
| `GET` | `/api/cards/stats` | `{ total, byType, byColor }`, y compris les compteurs à zéro. |

Les réponses de carte conservent explicitement les propriétés `null` afin de rester conformes au schéma.

## 6. Faire évoluer le contrat

1. modifier d'abord `backend/src/main/resources/schema/card-schema.json` ;
2. adapter `scraper/models.py`, la normalisation et les tests pytest ;
3. adapter `CardImportData`, `Card`, `CardResponse` et les tests Maven ;
4. adapter `frontend/src/types/card.ts` et `CardPreview.vue` ;
5. exécuter `pytest`, `mvn test` et `npm run build` avant la PR.
