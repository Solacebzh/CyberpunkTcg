"""Tests du pipeline complet : fixtures → normalisation → validation → export."""

from __future__ import annotations

import json

from cyberpunk_scraper.export import load_json_schema, validate_against_schema, write_exports
from cyberpunk_scraper.fixtures import load_fixture_cards
from cyberpunk_scraper.parse import parse_all

EXPECTED_FIXTURE_CARD_COUNT = 8  # la 9ᵉ entrée du jeu de test est volontairement invalide


def _build_cards():
    return parse_all(load_fixture_cards())


def test_fixtures_produce_cards_and_one_expected_rejection():
    cards, errors = _build_cards()

    assert len(cards) == EXPECTED_FIXTURE_CARD_COUNT
    assert len(errors) == 1
    assert errors[0].label == "Incomplete Test Card"


def test_every_card_matches_the_json_schema():
    cards, _ = _build_cards()
    assert validate_against_schema(cards, load_json_schema()) == []


def test_write_exports_creates_all_files_and_manifest(tmp_path):
    cards, errors = _build_cards()
    manifest = write_exports(cards, tmp_path, source="tests", errors=errors)

    for filename in ("cards.json", "cards.jsonl", "manifest.json"):
        assert (tmp_path / filename).exists(), filename

    assert manifest["count"] == EXPECTED_FIXTURE_CARD_COUNT
    assert manifest["schemaValidation"] == "ok"
    assert manifest["sets"] == ["WTNC"]
    assert manifest["byType"]["legend"] == 3
    assert manifest["byColor"]["green"] == 2
    assert len(manifest["rejected"]) == 1

    exported = json.loads((tmp_path / "cards.json").read_text(encoding="utf-8"))
    assert {card["id"] for card in exported} == {card.id for card in cards}

    # Le JSONL contient exactement une ligne par carte
    lines = (tmp_path / "cards.jsonl").read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == EXPECTED_FIXTURE_CARD_COUNT


def test_export_is_stable_across_two_runs(tmp_path):
    """Deux runs identiques produisent exactement le même contenu (hors manifeste)."""
    cards, _ = _build_cards()

    write_exports(cards, tmp_path / "run1", source="tests")
    write_exports(cards, tmp_path / "run2", source="tests")

    assert (tmp_path / "run1" / "cards.json").read_text(encoding="utf-8") == (
        tmp_path / "run2" / "cards.json"
    ).read_text(encoding="utf-8")
