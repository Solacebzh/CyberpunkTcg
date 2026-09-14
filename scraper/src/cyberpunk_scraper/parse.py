"""Conversion d'un enregistrement NetDeck brut vers une ``GameCard`` validée."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from pydantic import ValidationError

from .models import GameCard
from .normalize import (
    build_card_id,
    clean_text,
    normalize_color,
    normalize_keywords,
    normalize_rarity,
    normalize_set_code,
    normalize_type,
    parse_int,
    split_abilities,
    split_tags,
)


class CardParseError(ValueError):
    """Carte inexploitable, collectée sans interrompre le reste du catalogue."""

    def __init__(self, label: str, reason: str) -> None:
        super().__init__(f"{label} : {reason}")
        self.label = label
        self.reason = reason


def _first(raw: Mapping[str, Any], *keys: str) -> Any:
    for key in keys:
        value = raw.get(key)
        if value not in (None, "", []):
            return value
    return None


def _strings(value: Any) -> list[str]:
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        return [clean_text(str(item)) for item in value if clean_text(str(item))]
    return split_tags(str(value)) if value else []


def raw_to_card(raw: Mapping[str, Any]) -> GameCard:
    """Normalise et valide une carte de l'API officielle."""
    name = clean_text(_first(raw, "name", "title", "cardName"))
    if not name:
        raise CardParseError("<carte sans nom>", "nom manquant")

    subtitle = clean_text(_first(raw, "subtitle", "subname", "epithet")) or None
    type_ = normalize_type(str(_first(raw, "type", "cardType", "card_type") or ""))
    color = normalize_color(str(_first(raw, "color", "colour", "faction") or ""))
    if type_ is None:
        raise CardParseError(name, f"type inconnu ({_first(raw, 'type', 'cardType', 'card_type')!r})")
    if color is None:
        raise CardParseError(name, f"couleur inconnue ({_first(raw, 'color', 'colour', 'faction')!r})")

    set_code = normalize_set_code(str(_first(raw, "setCode", "set", "set_code") or ""))
    collector_number = clean_text(_first(raw, "collectorNumber", "printNumber", "print_number", "number") or "")
    if not set_code or not collector_number:
        raise CardParseError(name, "setCode/collectorNumber manquant")

    raw_text = str(_first(raw, "text", "rulesText", "rules_text", "effect", "ability") or "")
    text = clean_text(raw_text)
    supplied_abilities = _first(raw, "abilities")
    abilities = _strings(supplied_abilities) if supplied_abilities else split_abilities(raw_text)
    raw_tags = _first(raw, "tags", "traits", "classifications")
    tags = _strings(raw_tags)
    raw_keywords = _first(raw, "keywords", "keyword")
    keyword_source = " ".join(_strings(raw_keywords)) if raw_keywords else ""

    payload: dict[str, Any] = {
        "id": clean_text(_first(raw, "id", "slug")) or build_card_id(set_code, collector_number, name, subtitle),
        "name": name,
        "subtitle": subtitle,
        "type": type_.value,
        "color": color.value,
        "ram": parse_int(_first(raw, "ram", "ramValue")) or 0,
        "cost": parse_int(_first(raw, "cost", "eddies", "costEddies")),
        "power": parse_int(_first(raw, "power", "strength")),
        "streetCred": parse_int(_first(raw, "streetCred", "street_cred", "cred")),
        "tags": tags,
        "keywords": [keyword.value for keyword in normalize_keywords(keyword_source, text)],
        "text": text,
        "abilities": abilities,
        "imageUrl": _first(raw, "imageUrl", "source_image_url", "image", "image_url"),
        "setCode": set_code,
        "collectorNumber": collector_number,
        "rarity": (rarity.value if (rarity := normalize_rarity(str(_first(raw, "rarity") or ""))) else None),
    }

    try:
        return GameCard.model_validate(payload)
    except ValidationError as error:
        first_error = error.errors()[0]
        raise CardParseError(name, f"{first_error['loc']} → {first_error['msg']}") from error


def parse_all(raw_cards: list[Mapping[str, Any]]) -> tuple[list[GameCard], list[CardParseError]]:
    """Convertit le catalogue en collectant cartes invalides et identifiants dupliqués."""
    cards: list[GameCard] = []
    errors: list[CardParseError] = []
    seen_ids: set[str] = set()

    for raw in raw_cards:
        try:
            card = raw_to_card(raw)
            if card.id in seen_ids:
                raise CardParseError(card.name, f"identifiant dupliqué ({card.id})")
            seen_ids.add(card.id)
            cards.append(card)
        except CardParseError as error:
            errors.append(error)

    return cards, errors
