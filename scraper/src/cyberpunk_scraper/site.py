"""Extraction paginée du catalogue utilisé par ``cyberpunktcg.com``.

Le site officiel est alimenté par l'API JSON publique NetDeck. Utiliser cette
source structurée évite un parsing HTML fragile tout en reproduisant exactement
les requêtes du site. Le cache, les ré-essais et le délai entre appels sont
fournis par :class:`cyberpunk_scraper.fetch.HttpClient`.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlsplit, urlunsplit

from .fetch import HttpClient

logger = logging.getLogger(__name__)

CARDS_URL = "https://cyberpunktcg.com/cards"
CARDS_API_URL = "https://api.netdeck.gg/api/cards/cyberpunk"
PAGE_SIZE = 100  # maximum actuellement accepté par l'API
SIGNED_URL_PARAMS = {"Expires", "Signature", "Key-Pair-Id", "Policy"}


def _stable_url(value: Any) -> str | None:
    """Préfère des URLs reproductibles aux signatures CloudFront temporaires."""
    if not isinstance(value, str) or not value:
        return None
    parts = urlsplit(value)
    if any(f"{parameter}=" in parts.query for parameter in SIGNED_URL_PARAMS):
        return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
    return value


def map_api_card(node: dict[str, Any]) -> dict[str, Any]:
    """Traduit un item NetDeck vers le format brut commun du pipeline."""
    set_info = node.get("set") if isinstance(node.get("set"), dict) else {}
    return {
        "id": node.get("slug"),
        "name": node.get("name") or node.get("display_name"),
        "subtitle": node.get("subname"),
        "type": node.get("card_type"),
        "color": node.get("color"),
        "ram": node.get("ram"),
        "cost": node.get("cost"),
        "power": node.get("power"),
        "streetCred": node.get("street_cred"),
        "tags": node.get("classifications") or [],
        "keywords": node.get("keywords") or [],
        "text": node.get("rules_text") or "",
        "imageUrl": _stable_url(node.get("source_image_url") or node.get("image_url")),
        "setCode": set_info.get("code") or node.get("set_code"),
        "collectorNumber": node.get("print_number") or node.get("collector_number"),
        "rarity": node.get("rarity"),
    }


def _get_page(client: HttpClient, api_url: str, *, limit: int, offset: int) -> dict[str, Any]:
    separator = "&" if "?" in api_url else "?"
    url = f"{api_url}{separator}{urlencode({'limit': limit, 'offset': offset})}"
    logger.info("GET %s", url)
    try:
        payload = json.loads(client.get(url).text)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"Réponse JSON invalide pour {url}") from error
    if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
        raise RuntimeError(f"Réponse NetDeck inattendue pour {url}: champ items absent")
    return payload


def fetch_site_cards(
    client: HttpClient,
    url: str = CARDS_API_URL,
    dump_dir: str | None = None,
    *,
    page_size: int = PAGE_SIZE,
) -> list[dict[str, Any]]:
    """Récupère toutes les pages, refuse les lectures partielles et déduplique les slugs."""
    if page_size < 1 or page_size > PAGE_SIZE:
        raise ValueError(f"page_size doit être compris entre 1 et {PAGE_SIZE}")

    pages: list[dict[str, Any]] = []
    items: list[dict[str, Any]] = []
    offset = 0
    total: int | None = None

    while total is None or len(items) < total:
        page = _get_page(client, url, limit=page_size, offset=offset)
        pages.append(page)
        page_items = page["items"]
        reported_total = int(page.get("total", len(page_items)))
        if total is None:
            total = reported_total
        elif total != reported_total:
            raise RuntimeError(f"Le catalogue a changé pendant la pagination ({total} → {reported_total})")
        if not page_items and len(items) < total:
            raise RuntimeError(f"Catalogue tronqué : {len(items)} cartes reçues sur {total}")
        items.extend(item for item in page_items if isinstance(item, dict))
        offset += len(page_items)

    assert total is not None
    if len(items) != total:
        raise RuntimeError(f"Catalogue tronqué : {len(items)} cartes reçues sur {total}")

    slugs = [item.get("slug") for item in items]
    if None in slugs or len(slugs) != len(set(slugs)):
        raise RuntimeError("Catalogue invalide : slug absent ou dupliqué pendant la pagination")

    if dump_dir:
        path = Path(dump_dir)
        path.mkdir(parents=True, exist_ok=True)
        (path / "cards-api-pages.json").write_text(
            json.dumps(pages, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    mapped = [map_api_card(item) for item in items]
    mapped.sort(key=lambda card: str(card["id"]))
    logger.info("%s cartes récupérées en %s page(s)", len(mapped), len(pages))
    return mapped
