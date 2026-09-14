"""Conversion « carte brute » (issue du site ou des fixtures) → `GameCard` validée."""

from __future__ import annotations

from typing import Any, Mapping

from pydantic import ValidationError

from .models import GameCard
from .normalize import (
    build_card_id,
    clean_text,
    normalize_color,
    normalize_keywords,
    normalize_rarity,
    normalize_type,
    parse_int,
    split_tags,
)


class CardParseError(ValueError):
    """Carte inexploitable : on la signale sans interrompre tout le scraping."""

    def __init__(self, label: str, reason: str) -> None:
        super().__init__(f"{label} : {reason}")
        self.label = label
        self.reason = reason


def _first(raw: Mapping[str, Any], *keys: str) -> Any:
    """Première valeur non vide parmi plusieurs noms de champs possibles."""
    for key in keys:
        value = raw.get(key)
        if value not in (None, "", []):
            return value
    return None


def raw_to_card(raw: Mapping[str, Any]) -> GameCard:
    """Normalise une carte brute et la valide contre le modèle.

    :raises CardParseError: si un champ indispensable manque ou est incohérent.
    """
    name = clean_text(_first(raw, "name", "title", "cardName"))
    if not name:
        raise CardParseError("<carte sans nom>", "nom manquant")

    subtitle = clean_text(_first(raw, "subtitle", "epithet")) or None
    type_ = normalize_type(str(_first(raw, "type", "cardType") or ""))
    color = normalize_color(str(_first(raw, "color", "colour", "faction") or ""))

    if type_ is None:
        raise CardParseError(name, f"type inconnu ({_first(raw, 'type', 'cardType')!r})")
    if color is None:
        raise CardParseError(name, f"couleur inconnue ({_first(raw, 'color', 'colour')!r})")

    set_code = clean_text(_first(raw, "setCode", "set", "set_code") or "").upper()
    collector_number = clean_text(_first(raw, "collectorNumber", "number", "cardNumber") or "")
    if not set_code or not collector_number:
        raise CardParseError(name, "setCode/collectorNumber manquant")

    text = clean_text(_first(raw, "text", "rulesText", "effect", "ability"))

    payload: dict[str, Any] = {
        "id": clean_text(_first(raw, "id")) or build_card_id(set_code, collector_number, name, subtitle),
        "name": name,
        "subtitle": subtitle,
        "type": type_.value,
        "color": color.value,
        "ram": parse_int(_first(raw, "ram", "ramValue")) or 0,
        "cost": parse_int(_first(raw, "cost", "eddies", "costEddies")),
        "power": parse_int(_first(raw, "power", "strength")),
        "streetCred": parse_int(_first(raw, "streetCred", "street_cred", "cred")),
        "tags": split_tags(str(_first(raw, "tags", "traits") or "")),
        "keywords": [
            keyword.value
            for keyword in normalize_keywords(
                str(_first(raw, "keywords", "keyword") or ""),
                text,
            )
        ],
        "text": text,
        "flavorText": clean_text(_first(raw, "flavorText", "flavor", "flavour")) or None,
        "setCode": set_code,
        "collectorNumber": collector_number,
        "rarity": (rarity.value if (rarity := normalize_rarity(str(_first(raw, "rarity") or ""))) else None),
        "imageUrl": _first(raw, "imageUrl", "image", "image_url"),
    }

    # Une Legend est jouée face cachée : pas de coût en Eddies dans les données.
    if type_.value == "legend":
        payload["cost"] = None

    try:
        return GameCard.model_validate(payload)
    except ValidationError as error:
        first_error = error.errors()[0]
        raise CardParseError(name, f"{first_error['loc']} → {first_error['msg']}") from error


def parse_all(raw_cards: list[Mapping[str, Any]]) -> tuple[list[GameCard], list[CardParseError]]:
    """Convertit une liste de cartes brutes, en collectant les erreurs rencontrées."""
    cards: list[GameCard] = []
    errors: list[CardParseError] = []

    for raw in raw_cards:
        try:
            cards.append(raw_to_card(raw))
        except CardParseError as error:
            errors.append(error)

    return cards, errors
