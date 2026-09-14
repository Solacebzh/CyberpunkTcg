"""Jeu de cartes hors-ligne.

Permet de faire tourner tout le pipeline (normalisation → validation → export) et les
tests **sans accès réseau**, et sert de référence pour vérifier le format attendu
par `parse.raw_to_card`. Les valeurs sont des exemples de travail, pas des données
officielles : la feature 02 remplacera cette source par le scraping du site.
"""

from __future__ import annotations

from typing import Any

FIXTURE_CARDS: list[dict[str, Any]] = [
    {
        "name": "Saburo Arasaka",
        "subtitle": "Stubborn Patriarch",
        "type": "Legend",
        "color": "Green",
        "ram": "2",
        "tags": "Arasaka, Corpo",
        "text": "Flip: gagnez 1 Eddie. Vos Unités ARASAKA coûtent 1 Eddie de moins.",
        "flavorText": "« La loyauté se construit sur des décennies. »",
        "setCode": "WTNC",
        "collectorNumber": "A029",
        "rarity": "Legendary",
        "imageUrl": "https://ik.imagekit.io/cardnexus/production/cyberpunk/a029.png",
    },
    {
        "name": "Goro Takemura",
        "subtitle": "Hands Unclean",
        "type": "Legend",
        "color": "Green",
        "ram": "2",
        "tags": "Arasaka",
        "text": "Flip: choisissez une Unité alliée, elle gagne +1 Puissance.",
        "setCode": "WTNC",
        "collectorNumber": "A030",
        "rarity": "Rare",
    },
    {
        "name": "Yorinobu Arasaka",
        "subtitle": "Embracing Destruction",
        "type": "Legend",
        "color": "Red",
        "ram": "2",
        "tags": "Arasaka, Corpo",
        "text": "Flip: défaussez une carte pour piocher une carte.",
        "setCode": "WTNC",
        "collectorNumber": "A031",
        "rarity": "Rare",
    },
    {
        "name": "Night City Solo",
        "subtitle": "Gun for Hire",
        "type": "Unit",
        "color": "Red",
        "ram": "3",
        "cost": "4",
        "power": "5",
        "streetCred": "1",
        "tags": "Mercs, Solo",
        "text": "Go Solo. Quand cette Unité attaque, volez 1 Gig si sa Puissance dépasse celle du défenseur.",
        "setCode": "WTNC",
        "collectorNumber": "U112",
        "rarity": "Rare",
    },
    {
        "name": "Kiroshi Optics",
        "subtitle": "Mark III",
        "type": "Gear",
        "color": "Blue",
        "ram": "2",
        "cost": "2",
        "streetCred": "2",
        "tags": "Cyberware",
        "text": "Attachez à une Unité alliée. Play: cette Unité ne peut pas être bloquée par une Unité de Puissance 4 ou moins.",
        "setCode": "WTNC",
        "collectorNumber": "G045",
        "rarity": "Uncommon",
    },
    {
        "name": "Breach Protocol",
        "type": "Program",
        "color": "Blue",
        "ram": "1",
        "cost": "1",
        "tags": "Netrunner, Quickhack",
        "text": "Play: une Unité adverse perd 2 Puissance jusqu'à la fin du tour. Quick.",
        "setCode": "WTNC",
        "collectorNumber": "P078",
        "rarity": "Common",
    },
    {
        "name": "Trauma Team Medic",
        "subtitle": "Platinum Cardholder",
        "type": "Unit",
        "color": "Yellow",
        "ram": "2",
        "cost": "3",
        "power": "4",
        "streetCred": "2",
        "tags": "Corpo, Trauma Team",
        "text": "Blocker. Play: soignez 2 Puissance sur une Unité alliée.",
        "setCode": "WTNC",
        "collectorNumber": "U140",
        "rarity": "Uncommon",
    },
    {
        "name": "Afterlife Bar",
        "type": "Gear",
        "color": "Yellow",
        "ram": "3",
        "cost": "3",
        "tags": "Location, Afterlife",
        "text": "Play: dégâts 1 Gig depuis votre aire de Gigs vers votre Fixer Area.",
        "setCode": "WTNC",
        "collectorNumber": "G061",
        "rarity": "Rare",
    },
    # Carte volontairement invalide : sert à vérifier la robustesse de l'import
    {
        "name": "Incomplete Test Card",
        "type": "Unit",
        "color": "Purple",  # couleur inexistante → doit être rejetée
        "setCode": "WTNC",
        "collectorNumber": "Z999",
    },
]


def load_fixture_cards() -> list[dict[str, Any]]:
    """Copie du jeu de test (les cartes brutes ne doivent jamais être mutées)."""
    return [dict(card) for card in FIXTURE_CARDS]
