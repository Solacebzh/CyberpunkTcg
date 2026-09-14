"""Modèle de carte — contrat partagé avec le backend Java et le client TypeScript.

Le JSON produit ici doit valider `docs/schemas/card.schema.json`, qui est la
référence unique (les trois implémentations en découlent).
"""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel


class CardType(str, Enum):
    LEGEND = "legend"
    UNIT = "unit"
    PROGRAM = "program"
    GEAR = "gear"


class CardColor(str, Enum):
    """Quatre couleurs du système « Color Tree »."""

    RED = "red"
    GREEN = "green"
    BLUE = "blue"
    YELLOW = "yellow"


class CardRarity(str, Enum):
    COMMON = "common"
    UNCOMMON = "uncommon"
    RARE = "rare"
    LEGENDARY = "legendary"


class CardKeyword(str, Enum):
    GO_SOLO = "go_solo"
    BLOCKER = "blocker"
    QUICK = "quick"
    FLIP = "flip"
    PLAY = "play"
    ATTACK = "attack"


class GameCard(BaseModel):
    """Une carte du jeu, telle que stockée puis servie par le backend."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )

    id: str = Field(min_length=3, description="Slug unique et stable, ex. « wtnc-a029-saburo-arasaka »")
    name: str = Field(min_length=1)
    subtitle: str | None = None
    type: CardType
    color: CardColor
    ram: int = Field(ge=0, le=6)
    cost: int | None = Field(default=None, ge=0, le=20)
    power: int | None = Field(default=None, ge=0, le=20)
    street_cred: int | None = Field(default=None, ge=0, le=20)
    tags: list[str] = Field(default_factory=list)
    keywords: list[CardKeyword] = Field(default_factory=list)
    text: str = ""
    flavor_text: str | None = None
    set_code: str = Field(min_length=2, max_length=8)
    collector_number: str = Field(min_length=1, max_length=8)
    rarity: CardRarity | None = None
    image_url: str | None = None

    # --- Normalisations ---

    @field_validator("tags", mode="after")
    @classmethod
    def _normalize_tags(cls, tags: list[str]) -> list[str]:
        """Tags en majuscules, sans doublon, ordre stable."""
        return sorted({tag.strip().upper() for tag in tags if tag.strip()})

    @field_validator("keywords", mode="after")
    @classmethod
    def _dedupe_keywords(cls, keywords: list[CardKeyword]) -> list[CardKeyword]:
        seen: list[CardKeyword] = []
        for keyword in keywords:
            if keyword not in seen:
                seen.append(keyword)
        return seen

    @field_validator("set_code", mode="after")
    @classmethod
    def _upper_set_code(cls, set_code: str) -> str:
        return set_code.upper()

    @model_validator(mode="after")
    def _check_type_specific_rules(self) -> "GameCard":
        """Garde-fous issus des règles : seules les Units combattent, les Legends n'ont pas de coût."""
        if self.type is CardType.UNIT and self.power is None:
            raise ValueError(f"« {self.name} » : une Unit doit avoir une Puissance")
        if self.type is not CardType.UNIT and self.power is not None:
            raise ValueError(f"« {self.name} » : la Puissance est réservée aux Units")
        if self.type is CardType.LEGEND and self.cost is not None:
            raise ValueError(f"« {self.name} » : une Legend n'a pas de coût en Eddies")
        return self

    def to_json_dict(self) -> dict[str, Any]:
        """Dictionnaire sérialisé avec les alias camelCase (contrat du schéma JSON)."""
        return self.model_dump(by_alias=True, exclude_none=False)
