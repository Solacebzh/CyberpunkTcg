"""Fonctions de normalisation : du texte brut (HTML ou fixtures) vers le modèle de carte.

Ces fonctions sont volontairement pures et testées (`tests/test_normalize.py`) :
tout ce qui est « sale » dans l'extraction est isolé ici.
"""

from __future__ import annotations

import re
import unicodedata

from .models import CardColor, CardKeyword, CardRarity, CardType

# --- Correspondances « texte du site » → valeurs du modèle --------------------

TYPE_ALIASES: dict[str, CardType] = {
    "legend": CardType.LEGEND,
    "legends": CardType.LEGEND,
    "leader": CardType.LEGEND,
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
    "legendary": CardRarity.LEGENDARY,
    "legendaire": CardRarity.LEGENDARY,
    "légendaire": CardRarity.LEGENDARY,
}

KEYWORD_ALIASES: dict[str, CardKeyword] = {
    "go solo": CardKeyword.GO_SOLO,
    "go_solo": CardKeyword.GO_SOLO,
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
    """Extrait le premier entier d'une chaîne (« RAM 2 », « 4 Eddies » → 2, 4)."""
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return int(value)

    match = INT_PATTERN.search(value)
    return int(match.group()) if match else None


def normalize_type(raw: str | None) -> CardType | None:
    if not raw:
        return None
    return TYPE_ALIASES.get(strip_accents(raw).strip().lower())


def normalize_color(raw: str | None) -> CardColor | None:
    if not raw:
        return None
    return COLOR_ALIASES.get(strip_accents(raw).strip().lower())


def normalize_rarity(raw: str | None) -> CardRarity | None:
    if not raw:
        return None
    return RARITY_ALIASES.get(strip_accents(raw).strip().lower())


def normalize_keywords(*sources: str | None) -> list[CardKeyword]:
    """Repère les mots-clés de règles mentionnés dans le texte ou les champs dédiés."""
    found: list[CardKeyword] = []
    haystack = " ".join(source for source in sources if source)
    lowered = strip_accents(haystack).lower()

    for alias, keyword in KEYWORD_ALIASES.items():
        haystack_alias = strip_accents(alias).lower()
        if re.search(rf"\b{re.escape(haystack_alias)}\b", lowered) and keyword not in found:
            found.append(keyword)

    return found


def split_tags(raw: str | None) -> list[str]:
    """« Arasaka / Corpo, Mercs » → ['ARASAKA', 'CORPO', 'MERCS']."""
    if not raw:
        return []
    parts = re.split(r"[,/|·;]|\s{2,}", raw)
    return [strip_accents(part).strip().upper() for part in parts if part.strip()]


def clean_text(raw: str | None) -> str:
    """Supprime les espaces multiples et les retours chariot issus du HTML."""
    if not raw:
        return ""
    return re.sub(r"\s+", " ", raw).strip()


def slugify(value: str) -> str:
    """Identifiant lisible : accents retirés, minuscules, tirets (URL-safe)."""
    ascii_value = strip_accents(value).lower()
    value = re.sub(r"[^a-z0-9]+", "-", ascii_value).strip("-")
    return re.sub(r"-{2,}", "-", value)


def build_card_id(set_code: str, collector_number: str, name: str, subtitle: str | None = None) -> str:
    """Identifiant stable : `wtnc-a029-saburo-arasaka` (sous-titre inclus s'il existe)."""
    segments = [
        slugify(set_code),
        slugify(collector_number),
        slugify(f"{name} {subtitle}" if subtitle else name),
    ]
    return "-".join(segment for segment in segments if segment)
