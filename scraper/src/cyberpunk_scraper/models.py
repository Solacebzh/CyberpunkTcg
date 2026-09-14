"""Modèle Pydantic miroir de ``backend/.../schema/card-schema.json``."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel


class CardType(str, Enum):
    LEGEND = "legend"
    UNIT = "unit"
    PROGRAM = "program"
    GEAR = "gear"


class CardColor(str, Enum):
    RED = "red"
    GREEN = "green"
    BLUE = "blue"
    YELLOW = "yellow"


class CardRarity(str, Enum):
    COMMON = "common"
    UNCOMMON = "uncommon"
    RARE = "rare"
    EPIC = "epic"
    SECRET = "secret"
    ICONIC = "iconic"
    NOVA = "nova"
    PROMO = "promo"


class CardKeyword(str, Enum):
    GO_SOLO = "go_solo"
    BLOCKER = "blocker"
    QUICK = "quick"
    FLIP = "flip"
    PLAY = "play"
    ATTACK = "attack"


class GameCard(BaseModel):
    """Carte normalisée, prête à être exportée puis importée par Spring."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )

    id: str = Field(min_length=1, max_length=180, pattern=r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
    name: str = Field(min_length=1, max_length=160)
    subtitle: str | None = Field(default=None, max_length=200)
    type: CardType
    color: CardColor
    ram: int = Field(ge=0, le=20)
    cost: int | None = Field(default=None, ge=0, le=50)
    power: int | None = Field(default=None, ge=0, le=100)
    street_cred: int | None = Field(default=None, ge=0, le=100)
    tags: list[str] = Field(default_factory=list)
    keywords: list[CardKeyword] = Field(default_factory=list)
    text: str = Field(default="", max_length=10_000)
    abilities: list[str] = Field(default_factory=list)
    image_url: str | None = Field(default=None, max_length=2048)
    set_code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z0-9]+$")
    collector_number: str = Field(min_length=1, max_length=32)
    rarity: CardRarity | None = None

    @field_validator("tags", mode="after")
    @classmethod
    def _normalize_tags(cls, tags: list[str]) -> list[str]:
        return sorted({tag.strip().upper() for tag in tags if tag.strip()})

    @field_validator("keywords", mode="after")
    @classmethod
    def _dedupe_keywords(cls, keywords: list[CardKeyword]) -> list[CardKeyword]:
        return list(dict.fromkeys(keywords))

    @field_validator("abilities", mode="after")
    @classmethod
    def _clean_abilities(cls, abilities: list[str]) -> list[str]:
        return list(dict.fromkeys(ability.strip() for ability in abilities if ability.strip()))

    @field_validator("set_code", mode="after")
    @classmethod
    def _upper_set_code(cls, set_code: str) -> str:
        return set_code.upper()

    def to_json_dict(self) -> dict[str, Any]:
        """Sérialise avec les noms camelCase du contrat JSON."""
        return self.model_dump(by_alias=True, exclude_none=False)
