"""Écriture des exports : `cards.json`, `cards.jsonl`, `manifest.json` et provenance.

- `cards.json` : tableau conforme à `backend/src/main/resources/schema/card-schema.json`, prêt à être
  importé par le backend.
- `cards.jsonl` : une carte par ligne, pratique pour un import en flux ou une revue diff.
- `manifest.json` : compte, empreintes SHA-256, source, horodatage, erreurs — la traçabilité
  complète d'un run de scraping.
- `provenance.json` : d'où vient chaque carte (URL, date) ; gardé hors du fichier de cartes pour
  que `cards.json` reste strictement au format du schéma.
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator

from .models import GameCard
from .parse import CardParseError

DEFAULT_SCHEMA_PATH = (
    Path(__file__).resolve().parents[3]
    / "backend"
    / "src"
    / "main"
    / "resources"
    / "schema"
    / "card-schema.json"
)


def load_json_schema(schema_path: Path = DEFAULT_SCHEMA_PATH) -> dict[str, Any]:
    return json.loads(Path(schema_path).read_text(encoding="utf-8"))


def validate_against_schema(cards: Iterable[GameCard], schema: dict[str, Any]) -> list[str]:
    """Valide chaque carte contre le schéma JSON. Renvoie la liste des problèmes trouvés."""
    validator = Draft202012Validator(schema)
    problems: list[str] = []

    for card in cards:
        payload = card.to_json_dict()
        for error in sorted(validator.iter_errors(payload), key=lambda err: list(err.path)):
            location = ".".join(str(part) for part in error.path) or "<racine>"
            problems.append(f"{card.id} [{location}] {error.message}")

    return problems


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def write_exports(
    cards: list[GameCard],
    output_dir: Path,
    *,
    source: str,
    errors: list[CardParseError] | None = None,
    schema_path: Path = DEFAULT_SCHEMA_PATH,
    provenance: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Écrit tous les fichiers d'un run et renvoie le manifeste."""
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    cards_json = json.dumps([card.to_json_dict() for card in cards], ensure_ascii=False, indent=2) + "\n"
    (output_dir / "cards.json").write_text(cards_json, encoding="utf-8")

    lines = [json.dumps(card.to_json_dict(), ensure_ascii=False) for card in cards]
    (output_dir / "cards.jsonl").write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")

    schema = load_json_schema(schema_path)
    schema_problems = validate_against_schema(cards, schema)

    manifest: dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": source,
        "schema": str(Path(schema_path).name),
        "count": len(cards),
        "sets": sorted({card.set_code for card in cards}),
        "byType": {
            type_name: sum(1 for card in cards if card.type.value == type_name)
            for type_name in ("legend", "unit", "program", "gear")
        },
        "byColor": {
            color: sum(1 for card in cards if card.color.value == color)
            for color in ("red", "green", "blue", "yellow")
        },
        "sha256": {
            "cards.json": _sha256(cards_json),
            "cards.jsonl": _sha256("\n".join(lines) + ("\n" if lines else "")),
        },
        "schemaValidation": "ok" if not schema_problems else schema_problems,
        "rejected": [
            {"card": error.label, "reason": error.reason} for error in (errors or [])
        ],
    }

    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    if provenance:
        (output_dir / "provenance.json").write_text(
            json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    return manifest
