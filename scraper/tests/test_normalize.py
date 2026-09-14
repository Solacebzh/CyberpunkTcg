"""Tests des fonctions de normalisation et du mapping brut → modèle."""

from __future__ import annotations

import pytest

from cyberpunk_scraper.normalize import (
    build_card_id,
    clean_text,
    normalize_color,
    normalize_keywords,
    normalize_rarity,
    normalize_type,
    parse_int,
    slugify,
    split_tags,
)
from cyberpunk_scraper.parse import CardParseError, parse_all, raw_to_card


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("2", 2),
        ("RAM 3", 3),
        ("4 Eddies", 4),
        (5, 5),
        (None, None),
        ("aucun", None),
    ],
)
def test_parse_int(raw, expected):
    assert parse_int(raw) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("Legend", "legend"), ("UNITS", "unit"), ("program ", "program"), ("Gear", "gear"), ("Truc", None), (None, None)],
)
def test_normalize_type(raw, expected):
    result = normalize_type(raw)
    assert (result.value if result else None) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("Red", "red"), ("vert", "green"), ("Bleu", "blue"), ("Jaune", "yellow"), ("violet", None)],
)
def test_normalize_color(raw, expected):
    result = normalize_color(raw)
    assert (result.value if result else None) == expected


def test_normalize_rarity_accepts_official_tiers_and_accents():
    assert normalize_rarity("Épique").value == "epic"
    assert normalize_rarity("Iconic Rare").value == "iconic"
    assert normalize_rarity("peu commune").value == "uncommon"
    assert normalize_rarity(None) is None


def test_normalize_keywords_detects_rules_text():
    keywords = normalize_keywords("Blocker. Play: soignez 2 Puissance.", "GO SOLO")
    assert [keyword.value for keyword in keywords] == ["go_solo", "blocker", "play"]


def test_normalize_keywords_does_not_match_inside_words():
    # « blocking » ne doit pas déclencher BLOCKER
    assert normalize_keywords("This unit is blocking nothing.") == []


def test_split_tags_and_slugify():
    assert split_tags("Arasaka, Corpo / Mercs") == ["ARASAKA", "CORPO", "MERCS"]
    assert split_tags(None) == []
    assert slugify("Saburo Arasaka — Stubborn Patriarch!") == "saburo-arasaka-stubborn-patriarch"


def test_build_card_id_is_stable_and_readable():
    assert build_card_id("WTNC", "A029", "Saburo Arasaka", "Stubborn Patriarch") == "wtnc-a029-saburo-arasaka-stubborn-patriarch"
    assert build_card_id("WTNC", "A029", "Saburo Arasaka") == "wtnc-a029-saburo-arasaka"


def test_clean_text_collapses_whitespace():
    assert clean_text("  Jouez\n  cette   carte  ") == "Jouez cette carte"


def test_raw_to_card_maps_and_normalizes():
    card = raw_to_card(
        {
            "name": "Night City Solo",
            "subtitle": "Gun for Hire",
            "type": "Unit",
            "color": "Red",
            "ram": "RAM 3",
            "cost": "4 Eddies",
            "power": "5",
            "streetCred": "1",
            "tags": "Mercs, Solo",
            "text": "Go Solo. Quand cette Unité attaque…",
            "setCode": "wtnc",
            "collectorNumber": "u112",
            "rarity": "Rare",
        }
    )

    assert card.id == "wtnc-u112-night-city-solo-gun-for-hire"
    assert card.type.value == "unit"
    assert card.color.value == "red"
    assert (card.ram, card.cost, card.power, card.street_cred) == (3, 4, 5, 1)
    assert card.tags == ["MERCS", "SOLO"]
    assert [keyword.value for keyword in card.keywords] == ["go_solo"]
    assert card.set_code == "WTNC"

    # Le JSON exporté utilise bien les alias camelCase du schéma
    assert card.to_json_dict()["streetCred"] == 1


def test_raw_to_card_rejects_unknown_color():
    with pytest.raises(CardParseError) as error:
        raw_to_card({"name": "X", "type": "Unit", "color": "Purple", "setCode": "WTNC", "collectorNumber": "Z1"})
    assert "couleur inconnue" in str(error.value)


def test_legend_keeps_printed_cost_and_power():
    card = raw_to_card(
        {
            "name": "Adam Smasher",
            "subtitle": "Ender of Legends",
            "type": "Legend",
            "color": "Red",
            "ram": "2",
            "cost": "9",
            "power": "9",
            "setCode": "WTNC",
            "collectorNumber": "001",
        }
    )
    assert card.cost == 9
    assert card.power == 9


def test_parse_all_collects_errors_instead_of_failing():
    cards, errors = parse_all(
        [
            {"name": "Valide", "type": "Program", "color": "Blue", "ram": "1", "setCode": "WTNC", "collectorNumber": "P1"},
            {"name": "Couleur cassée", "type": "Unit", "color": "Orange", "setCode": "WTNC", "collectorNumber": "X1"},
        ]
    )
    assert len(cards) == 1
    assert len(errors) == 1
    assert errors[0].label == "Couleur cassée"
