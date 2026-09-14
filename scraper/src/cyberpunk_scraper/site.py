"""Extraction des cartes depuis cyberpunktcg.com.

⚠️ État : *squelette*. Le site rend sa base de cartes côté client (données injectées
dans la page). L'extraction est donc faite en deux temps :

1. chercher un bloc JSON embarqué (`__NEXT_DATA__`, `application/json`, `window.__DATA__`) ;
2. à défaut, retomber sur un parsing DOM des tuiles de cartes (sélecteurs à ajuster
   sur le HTML réel — `--dump-html` écrit la page dans `output/` pour l'inspection).

La normalisation, la validation et l'export, eux, sont complets et testés : brancher
les bons sélecteurs suffira à produire un fichier exploitable par le backend.
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Iterator

from bs4 import BeautifulSoup

from .fetch import HttpClient

logger = logging.getLogger(__name__)

CARDS_URL = "https://cyberpunktcg.com/cards"

# Clés reconnues comme « nom de l'image » dans les blocs JSON embarqués
IMAGE_KEY_PATTERN = re.compile(r"(image|img|art|render)", re.IGNORECASE)
JSON_BLOCK_PATTERN = re.compile(
    r'<script[^>]*type="application/json"[^>]*>(?P<body>.*?)</script>'
    r'|<script[^>]*>window\.__(?:NEXT_DATA|DATA|INITIAL_STATE)__\s*=\s*(?P<var>.*?)</script>',
    re.DOTALL,
)


def fetch_cards_html(client: HttpClient, url: str = CARDS_URL) -> str:
    """Récupère la page de la base de cartes (avec cache disque)."""
    logger.info("GET %s", url)
    return client.get(url).text


def extract_embedded_json(html: str) -> Iterator[Any]:
    """Itère les blocs JSON embarqués dans la page, en ignorant ceux qui sont illisibles."""
    for match in JSON_BLOCK_PATTERN.finditer(html):
        payload = match.group("body") or match.group("var")
        if not payload:
            continue
        try:
            yield json.loads(payload.strip())
        except (json.JSONDecodeError, TypeError):
            continue


def find_card_arrays(node: Any, *, min_items: int = 3) -> Iterator[list[dict[str, Any]]]:
    """Parcourt récursivement un JSON et renvoie les listes qui ressemblent à des cartes.

    Critère : au moins `min_items` éléments ayant chacun une clé « name ».
    """
    if isinstance(node, dict):
        for value in node.values():
            yield from find_card_arrays(value, min_items=min_items)
    elif isinstance(node, list) and len(node) >= min_items:
        dicts = [item for item in node if isinstance(item, dict) and "name" in item]
        if len(dicts) >= min_items:
            yield dicts
        for item in node:
            yield from find_card_arrays(item, min_items=min_items)


def map_json_card(node: dict[str, Any]) -> dict[str, Any]:
    """Traduit un objet carte récupéré du site vers le format brut attendu par `parse`."""
    image_url = None
    for key, value in node.items():
        if isinstance(value, str) and value.startswith("http") and IMAGE_KEY_PATTERN.search(key):
            image_url = value
            break

    tags_value = node.get("tags") or node.get("traits") or ""
    if isinstance(tags_value, list):
        tags_value = ", ".join(str(tag) for tag in tags_value)

    return {
        "name": node.get("name") or node.get("title") or "",
        "subtitle": node.get("subtitle") or node.get("epithet"),
        "type": node.get("type") or node.get("cardType"),
        "color": node.get("color") or node.get("colour") or node.get("faction"),
        "ram": node.get("ram") or node.get("ramValue"),
        "cost": node.get("cost") or node.get("eddies"),
        "power": node.get("power") or node.get("strength"),
        "streetCred": node.get("streetCred") or node.get("street_cred"),
        "tags": tags_value,
        "keywords": node.get("keywords") or node.get("keyword"),
        "text": node.get("text") or node.get("rulesText") or node.get("effect") or node.get("ability"),
        "flavorText": node.get("flavorText") or node.get("flavor"),
        "setCode": node.get("setCode") or node.get("set") or "",
        "collectorNumber": node.get("collectorNumber") or node.get("number") or "",
        "rarity": node.get("rarity"),
        "imageUrl": image_url,
    }


def parse_dom_cards(html: str) -> list[dict[str, Any]]:
    """Dernier recours : lecture des tuiles de cartes dans le HTML.

    ⚠️ Les sélecteurs ci-dessous sont volontairement génériques (`data-*`, classes
    contenant « card ») et devront être affinés sur le HTML réel du site.
    """
    soup = BeautifulSoup(html, "html.parser")
    cards: list[dict[str, Any]] = []

    candidates = soup.select("[data-card], .card, .card-tile, li[class*=card], article[class*=card]")
    for tile in candidates:
        name_node = tile.select_one("[data-name], .card-name, h2, h3, h4")
        name = name_node.get_text(strip=True) if name_node else None
        if not name:
            continue

        image_node = tile.select_one("img")
        cards.append(
            {
                "name": name,
                "type": tile.get("data-type"),
                "color": tile.get("data-color"),
                "ram": tile.get("data-ram"),
                "cost": tile.get("data-cost"),
                "power": tile.get("data-power"),
                "tags": tile.get("data-tags") or "",
                "text": (tile.select_one("[data-text], .card-text") or tile).get_text(" ", strip=True),
                "setCode": tile.get("data-set") or "",
                "collectorNumber": tile.get("data-number") or "",
                "imageUrl": image_node.get("src") if image_node else None,
            }
        )

    return cards


def fetch_site_cards(client: HttpClient, url: str = CARDS_URL, dump_dir: str | None = None) -> list[dict[str, Any]]:
    """Récupère les cartes depuis le site, en essayant les stratégies dans l'ordre."""
    html = fetch_cards_html(client, url)

    if dump_dir:
        from pathlib import Path

        dump_path = Path(dump_dir)
        dump_path.mkdir(parents=True, exist_ok=True)
        (dump_path / "cards-page.html").write_text(html, encoding="utf-8")
        logger.info("HTML brut écrit dans %s", dump_path / "cards-page.html")

    # 1) JSON embarqué
    for payload in extract_embedded_json(html):
        for cards_array in find_card_arrays(payload):
            mapped = [map_json_card(card) for card in cards_array]
            if mapped:
                logger.info("%s cartes trouvées dans un bloc JSON embarqué", len(mapped))
                return mapped

    # 2) Parsing DOM
    dom_cards = parse_dom_cards(html)
    if dom_cards:
        logger.info("%s cartes trouvées via le DOM", len(dom_cards))
        return dom_cards

    raise RuntimeError(
        "Aucune carte exploitable trouvée : le site a probablement changé de structure. "
        "Relancer avec --dump-html puis ajuster site.py (voir la doc du scraper)."
    )
