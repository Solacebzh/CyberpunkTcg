"""Ligne de commande du scraper.

Exemples :

    python -m cyberpunk_scraper cards --source fixtures
    python -m cyberpunk_scraper cards --source site --refresh --dump-html
    python -m cyberpunk_scraper validate output/cards.json
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path
from typing import Sequence

from .export import DEFAULT_SCHEMA_PATH, load_json_schema, validate_against_schema, write_exports
from .fetch import HttpClient
from .fixtures import load_fixture_cards
from .models import GameCard
from .parse import parse_all

DEFAULT_OUTPUT_DIR = Path("output")


def _configure_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(levelname)-7s %(message)s",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="cyberpunk-scraper",
        description="Récupère et normalise les cartes du Cyberpunk TCG en JSON.",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="journalisation détaillée")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # --- cards ---
    cards = subparsers.add_parser("cards", help="récupère les cartes et écrit les exports")
    cards.add_argument(
        "--source",
        choices=("fixtures", "site"),
        default="fixtures",
        help="fixtures = jeu hors-ligne (défaut) ; site = scraping de cyberpunktcg.com",
    )
    cards.add_argument("--out", type=Path, default=DEFAULT_OUTPUT_DIR, help="dossier de sortie (défaut : output/)")
    cards.add_argument("--limit", type=int, default=None, help="ne traiter que les N premières cartes")
    cards.add_argument("--refresh", action="store_true", help="ignorer le cache HTTP")
    cards.add_argument(
        "--dump-source",
        "--dump-html",
        dest="dump_source",
        action="store_true",
        help="écrire les réponses API agrégées pour inspection (--dump-html est un alias historique)",
    )
    cards.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA_PATH, help="schéma JSON de référence")
    cards.add_argument(
        "--fail-on-reject",
        action="store_true",
        help="sortir en erreur si au moins une carte est rejetée (utile en CI)",
    )

    # --- validate ---
    validate = subparsers.add_parser("validate", help="valide un fichier cards.json contre le schéma")
    validate.add_argument("path", type=Path, nargs="?", default=DEFAULT_OUTPUT_DIR / "cards.json")
    validate.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA_PATH)

    return parser


def cmd_cards(args: argparse.Namespace) -> int:
    if args.source == "site":
        from .site import fetch_site_cards

        with HttpClient(refresh=args.refresh, cache_dir=Path(".cache/http")) as client:
            raw_cards = fetch_site_cards(
                client,
                url="https://api.netdeck.gg/api/cards/cyberpunk",
                dump_dir=str(args.out) if args.dump_source else None,
            )
        provenance_note = "cyberpunktcg.com/cards"
    else:
        raw_cards = load_fixture_cards()
        provenance_note = "fixtures locales (scraper/src/cyberpunk_scraper/fixtures.py)"

    if args.limit is not None:
        raw_cards = raw_cards[: args.limit]

    cards, errors = parse_all(raw_cards)
    for error in errors:
        logging.warning("carte rejetée — %s", error)

    manifest = write_exports(cards, args.out, source=provenance_note, errors=errors, schema_path=args.schema)

    print(f"✅ {manifest['count']} cartes écrites dans {args.out}/cards.json (source : {provenance_note})")
    if manifest["rejected"]:
        print(f"⚠️  {len(manifest['rejected'])} carte(s) rejetée(s) — voir {args.out}/manifest.json")
    if manifest["schemaValidation"] != "ok":
        print(f"❌ validation de schéma en échec : {manifest['schemaValidation']}")
        return 1

    if errors and args.fail_on_reject:
        return 1

    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    path = Path(args.path)
    if not path.exists():
        print(f"❌ fichier introuvable : {path}", file=sys.stderr)
        return 2

    payload = json.loads(path.read_text(encoding="utf-8"))
    cards = [GameCard.model_validate(entry) for entry in payload]
    problems = validate_against_schema(cards, load_json_schema(args.schema))

    if problems:
        print(f"❌ {len(problems)} problème(s) détecté(s) dans {path} :")
        for problem in problems[:20]:
            print(f"   - {problem}")
        return 1

    print(f"✅ {len(cards)} cartes valides dans {path} (schéma : {args.schema.name})")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    _configure_logging(args.verbose)

    if args.command == "cards":
        return cmd_cards(args)
    if args.command == "validate":
        return cmd_validate(args)

    parser.print_help()
    return 2


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
