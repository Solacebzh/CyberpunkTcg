# scraper — Cartes data-driven (Python 3.11+)

Récupère les cartes du Cyberpunk TCG, les **normalise** et les exporte en JSON
conforme à [`../docs/schemas/card.schema.json`](../docs/schemas/card.schema.json).
Le backend ne parle jamais au site officiel : il lit le fichier produit ici.

```
fetch (HTTP + cache)  →  parse (normalisation)  →  models (validation)  →  export (JSON/JSONL/manifeste)
```

## Installation

```bash
cd scraper
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install -e .      # installe le paquet (sinon : PYTHONPATH=src python -m cyberpunk_scraper)
```

## Utilisation

```bash
# Pipeline complet hors-ligne (jeu de cartes de test embarqué) — recommandé pour démarrer
.venv/bin/python -m cyberpunk_scraper cards --source fixtures

# Sortie :
# output/cards.json       tableau de cartes conforme au schéma
# output/cards.jsonl      une carte par ligne
# output/manifest.json    compte, empreintes SHA-256, rejets, statistiques
# output/provenance.json  origine de chaque carte (avec --source site)

# Vérifier un export existant
.venv/bin/python -m cyberpunk_scraper validate output/cards.json

# Scraping du site officiel (cache disque, ré-essais, délai entre requêtes)
.venv/bin/python -m cyberpunk_scraper cards --source site --refresh --dump-html
```

| Option | Effet |
| --- | --- |
| `--source fixtures\|site` | Jeu hors-ligne (défaut) ou scraping de `cyberpunktcg.com` |
| `--out DOSSIER` | Dossier de sortie (défaut `output/`) |
| `--limit N` | Ne traiter que les N premières cartes |
| `--refresh` | Ignorer le cache HTTP (`scraper/.cache/`) |
| `--dump-html` | Écrire la page brute pour inspection |
| `--fail-on-reject` | Code de sortie 1 si une carte est rejetée (utile en CI) |

## Tests

```bash
.venv/bin/python -m pytest        # ou : make test (depuis la racine)
```

## État et limites connues

- **Pipeline complet et testé** : normalisation, validation, garde-fous de règles (une Unit a une Puissance, une Legend n'a pas de coût, couleurs/types imposés), export, manifeste, SHA-256.
- **`site.py` est un squelette** : la base de cartes du site est rendue côté client. L'extraction cherche d'abord un bloc JSON embarqué, puis retombe sur un parsing DOM aux sélecteurs génériques. Le branchement final des sélecteurs se fera sur le HTML réel (`--dump-html`) pendant la **feature 02**.
- **Respect du site** : `User-Agent` identifiant, délai d'1 s entre requêtes, cache disque pour éviter les appels répétés. À garder ainsi : usage privé et non commercial, volume très faible.
- Les données des fixtures sont des **valeurs de travail** (formats et bornes réalistes), pas des données officielles.

## Où brancher la suite

1. Ajuster `site.py` (`map_json_card` ou `parse_dom_cards`) sur le HTML réel.
2. Ajouter un connecteur de sortie : import direct en base via l'API du backend (`POST /api/cards/import`, feature 02) au lieu du seul export fichier.
3. Ajouter la déduplication des impressions/reprints (même nom, numéros différents).
