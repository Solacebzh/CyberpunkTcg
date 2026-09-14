"""Tests hors ligne de l'adaptateur paginé de cyberpunktcg.com / NetDeck."""

from __future__ import annotations

import json
from dataclasses import dataclass

import pytest

from cyberpunk_scraper.fetch import FetchResult
from cyberpunk_scraper.site import fetch_site_cards, map_api_card


@dataclass
class FakeClient:
    cards: list[dict]

    def get(self, url: str) -> FetchResult:
        offset = int(url.split("offset=")[1].split("&")[0])
        limit = int(url.split("limit=")[1].split("&")[0])
        payload = {"total": len(self.cards), "items": self.cards[offset : offset + limit]}
        return FetchResult(url=url, status_code=200, text=json.dumps(payload))


def api_card(slug: str, number: str) -> dict:
    return {
        "slug": slug,
        "name": slug.replace("-", " ").title(),
        "subname": None,
        "card_type": "Unit",
        "color": "Red",
        "ram": 1,
        "cost": 2,
        "power": 3,
        "classifications": ["Merc", "Solo"],
        "keywords": [],
        "rules_text": "{Play} Draw 1.\n{Quick} Ready this Unit.",
        "source_image_url": f"https://cdn.example/{slug}.webp",
        "set": {"code": "welcometonightcityretail", "name": "Welcome to Night City — Retail"},
        "print_number": number,
        "rarity": "Epic",
    }


def test_fetch_site_cards_paginates_and_sorts():
    cards = [api_card("z-card", "003"), api_card("a-card", "001"), api_card("m-card", "002")]
    result = fetch_site_cards(FakeClient(cards), page_size=2)

    assert [card["id"] for card in result] == ["a-card", "m-card", "z-card"]
    assert result[0]["setCode"] == "welcometonightcityretail"


def test_map_api_card_prefers_unsigned_source_image():
    raw = api_card("test-card", "007")
    raw["image_url"] = "https://cdn.example/signed.webp?Expires=1&Signature=secret"
    raw["source_image_url"] = "https://cdn.example/stable.webp"

    assert map_api_card(raw)["imageUrl"] == "https://cdn.example/stable.webp"


def test_fetch_site_cards_rejects_duplicate_slugs():
    duplicate = [api_card("same-card", "001"), api_card("same-card", "002")]
    with pytest.raises(RuntimeError, match="slug absent ou dupliqué"):
        fetch_site_cards(FakeClient(duplicate), page_size=2)
