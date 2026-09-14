"""Normalisation pure des valeurs exposées par l'API NetDeck."""

from __future__ import annotations

import re
import unicodedata

from .models import CardColor, CardKeyword, CardRarity, CardType

TYPE_ALIASES: dict[str, CardType] = {
    "legend": CardType.LEGEND,
    "legends": CardType.LEGEND,
    "unit": CardType.UNIT,
    "units": CardType.UNIT,
    "program": CardType.PROGRAM,
    "programs": CardType.PROGRAM,
    "gear": CardType.GEAR,
}

COLOR_ALIASES: dict[str, CardColor] = {
    "red": CardColor.RED,
    "rouge": CardColor.RED,
    "green": CardColor.GREEN,
    "vert": CardColor.GREEN,
    "blue": CardColor.BLUE,
    "bleu": CardColor.BLUE,
    "yellow": CardColor.YELLOW,
    "jaune": CardColor.YELLOW,
}

RARITY_ALIASES: dict[str, CardRarity] = {
    "common": CardRarity.COMMON,
    "commune": CardRarity.COMMON,
    "uncommon": CardRarity.UNCOMMON,
    "peu commune": CardRarity.UNCOMMON,
    "rare": CardRarity.RARE,
    "epic": CardRarity.EPIC,
    "epique": CardRarity.EPIC,
    "secret": CardRarity.SECRET,
    "secret rare": CardRarity.SECRET,
    "iconic": CardRarity.ICONIC,
    "iconic rare": CardRarity.ICONIC,
    "nova": CardRarity.NOVA,
    "nova rare": CardRarity.NOVA,
    "promo": CardRarity.PROMO,
    "promotional": CardRarity.PROMO,
}

KEYWORD_ALIASES: dict[str, CardKeyword] = {
    "go solo": CardKeyword.GO_SOLO,
    "go_solo": CardKeyword.GO_SOLO,
    "go-solo": CardKeyword.GO_SOLO,
    "blocker": CardKeyword.BLOCKER,
    "quick": CardKeyword.QUICK,
    "flip": CardKeyword.FLIP,
    "play": CardKeyword.PLAY,
    "attack": CardKeyword.ATTACK,
}

INT_PATTERN = re.compile(r"-?\d+")


def strip_accents(value: str) -> str:
    return "".join(char for char in unicodedata.normalize("NFD", value) if unicodedata.category(char) != "Mn")


def parse_int(value: str | int | float | None) -> int | None:
    """Extrait le premier entier (``RAM 2`` et ``4 Eddies`` sont acceptés)."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return int(value)
    match = INT_PATTERN.search(value)
    return int(match.group()) if match else None


def normalize_type(raw: str | None) -> CardType | None:
    return TYPE_ALIASES.get(strip_accents(raw).strip().lower()) if raw else None


def normalize_color(raw: str | None) -> CardColor | None:
    return COLOR_ALIASES.get(strip_accents(raw).strip().lower()) if raw else None


def normalize_rarity(raw: str | None) -> CardRarity | None:
    return RARITY_ALIASES.get(strip_accents(raw).strip().lower()) if raw else None


def normalize_keywords(*sources: str | None) -> list[CardKeyword]:
    """Détecte les mots-clés dans les champs ou le balisage ``{Quick}`` du texte."""
    found: list[CardKeyword] = []
    lowered = strip_accents(" ".join(source for source in sources if source)).lower()
    for alias, keyword in KEYWORD_ALIASES.items():
        if re.search(rf"\b{re.escape(strip_accents(alias).lower())}\b", lowered) and keyword not in found:
            found.append(keyword)
    return found


def split_tags(raw: str | None) -> list[str]:
    """``Arasaka / Corpo, Mercs`` devient trois tags."""
    if not raw:
        return []
    parts = re.split(r"[,/|·;]|\s{2,}", raw)
    return [strip_accents(part).strip().upper() for part in parts if part.strip()]


def clean_text(raw: str | None) -> str:
    if not raw:
        return ""
    return re.sub(r"\s+", " ", raw).strip()


def split_abilities(raw: str | None) -> list[str]:
    """Conserve chaque paragraphe d'effet dans l'ordre d'impression."""
    if not raw:
        return []
    paragraphs = re.split(r"(?:\r?\n)+", raw)
    return [clean_text(paragraph) for paragraph in paragraphs if clean_text(paragraph)]


def normalize_set_code(raw: str | None) -> str:
    """Normalise le code NetDeck en majuscules ASCII sans ponctuation."""
    return re.sub(r"[^A-Z0-9]", "", strip_accents(raw or "").upper())


def slugify(value: str) -> str:
    ascii_value = strip_accents(value).lower()
    value = re.sub(r"[^a-z0-9]+", "-", ascii_value).strip("-")
    return re.sub(r"-{2,}", "-", value)


def build_card_id(set_code: str, collector_number: str, name: str, subtitle: str | None = None) -> str:
    segments = [
        slugify(set_code),
        slugify(collector_number),
        slugify(f"{name} {subtitle}" if subtitle else name),
    ]
    return "-".join(segment for segment in segments if segment)
