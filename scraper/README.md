# Scraper de cartes (Python 3.11+)

Le scraper lit la base de cartes publique affichée par
[`cyberpunktcg.com/cards`](https://cyberpunktcg.com/cards). Le site s'appuie sur l'API JSON NetDeck :

```text
https://api.netdeck.gg/api/cards/cyberpunk?limit=100&offset=0
```

Le pipeline produit un JSON conforme au contrat
[`backend/src/main/resources/schema/card-schema.json`](../backend/src/main/resources/schema/card-schema.json).
Le backend ne contacte jamais le site tiers pendant une requête utilisateur.

```text
API NetDeck paginée → cache HTTP → normalisation → Pydantic → JSON Schema → exports
```

## Installation et tests

```bash
cd scraper
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install -e .
.venv/bin/python -m pytest
```

## Utilisation

```bash
# Jeu hors ligne reproductible (tests et développement)
.venv/bin/python -m cyberpunk_scraper cards --source fixtures

# Catalogue de cyberpunktcg.com ; les pages sont conservées dans .cache/http/
.venv/bin/python -m cyberpunk_scraper cards --source site

# Ignorer le cache et garder aussi les réponses API agrégées pour diagnostic
.venv/bin/python -m cyberpunk_scraper cards --source site --refresh --dump-source

# Revalider un export
.venv/bin/python -m cyberpunk_scraper validate output/cards.json
```

| Option | Effet |
| --- | --- |
| `--source fixtures\|site` | Source hors ligne ou catalogue officiel. |
| `--out DOSSIER` | Dossier de sortie (`output/` par défaut). |
| `--limit N` | Limite appliquée après récupération, utile pour un essai local. |
| `--refresh` | Ignore le cache HTTP. |
| `--dump-source` | Écrit `cards-api-pages.json` à côté des exports (`--dump-html` reste un alias compatible). |
| `--fail-on-reject` | Retourne 1 dès qu'une carte est rejetée. |

## Garanties

- pagination `limit/offset` jusqu'au champ `total` ;
- refus d'un catalogue tronqué, d'un total mouvant ou de slugs dupliqués ;
- ré-essais, timeout, User-Agent explicite, délai d'une seconde et cache par URL ;
- valeurs `type` / `color` en minuscules, tags en majuscules, raretés et mots-clés normalisés ;
- `source_image_url` stable préféré à l'URL CloudFront signée ;
- validation Pydantic puis JSON Schema avant qu'un export soit déclaré valide.

## Exports

| Fichier | Contenu |
| --- | --- |
| `cards.json` | Tableau importable par Spring. |
| `cards.jsonl` | Une carte par ligne. |
| `manifest.json` | Compte, statistiques, SHA-256, source et rejets. |
| `cards-api-pages.json` | Réponses de diagnostic uniquement avec `--dump-source`. |

Les images ne sont ni téléchargées ni versionnées : seule leur URL source stable est conservée.
