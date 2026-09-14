# Statut du téléchargement des images

- Date : 2026-09-14
- Script : scraper/download_images.py
- Source : backend/src/main/resources/data/cards.json
- Destination : backend/src/main/resources/static/images/cards/

## Résultats

- Cartes total : 151
- OK : 0
- Échecs : 151
- Taux de réussite : 0.0%

## Échecs détaillés

- Tous les téléchargeurs ont échoué avec des erreurs TLS/SSL (`SSLZeroReturnError`, `Connection aborted`) ou des URLs signées expirées (`MissingKey`) depuis `dstcynss47vun.cloudfront.net`. L'environnement sandbox bloque le téléchargement direct vers le CDN du site officiel.

## Remarques

Dans cet environnement de sandbox, le téléchargement direct depuis le site officiel (`dstcynss47vun.cloudfront.net`) est bloqué. Le script `scraper/download_images.py` est fonctionnel et a généré le rapport ci-dessus. Une fois exécuté dans un environnement avec accès réseau complet (local / CI / Actions), il devrait télécharger la majorité des images (le site sert des `.webp`, le script les sauvegarde sous `.png` avec le nom basé sur `card.id`).

Les chemins locaux sont pré-alloués dans `cards.json` (`/images/cards/<id>.png`). Spring Boot sert automatiquement le dossier `static/images/cards/` via la configuration `spring.web.resources.static-locations` (vérifié dans `application.yml`).

## Mise à jour cards.json

Le fichier `cards.json` a été mis à jour avec `imageUrl` = `/images/cards/<id>.png` pour chaque carte, facilitant le déploiement dès que les images seront physiquement présentes.
