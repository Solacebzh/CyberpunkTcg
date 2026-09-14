"""Client HTTP courtois : cache disque, ré-essais avec repli exponentiel, throttling.

Le cache évite de marteler le site officiel pendant le développement :
les mêmes URLs sont relues depuis `scraper/.cache/` tant que `--refresh` n'est pas passé.
"""

from __future__ import annotations

import hashlib
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path

import httpx

logger = logging.getLogger(__name__)

DEFAULT_USER_AGENT = (
    "CyberpunkTcgOnlineBot/0.1 (+projet privé entre amis ; contact: propriétaire du dépôt ; "
    "respecte robots.txt et un délai entre requêtes)"
)


@dataclass
class FetchResult:
    """Réponse HTTP (éventuellement issue du cache)."""

    url: str
    status_code: int
    text: str
    from_cache: bool = False


@dataclass
class HttpClient:
    """Petit client HTTP avec cache disque et politesse envers le serveur."""

    cache_dir: Path = field(default_factory=lambda: Path(".cache/http"))
    timeout: float = 20.0
    delay: float = 1.0
    retries: int = 3
    user_agent: str = DEFAULT_USER_AGENT
    refresh: bool = False

    _client: httpx.Client | None = field(default=None, init=False, repr=False)

    def __post_init__(self) -> None:
        self.cache_dir = Path(self.cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    # --- Cycle de vie ---

    @property
    def client(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(
                timeout=self.timeout,
                follow_redirects=True,
                headers={
                    "User-Agent": self.user_agent,
                    "Accept": "application/json,text/html;q=0.8,*/*;q=0.5",
                    "Accept-Language": "en-US,en;q=0.8,fr;q=0.7",
                    "Origin": "https://cyberpunktcg.com",
                    "Referer": "https://cyberpunktcg.com/",
                },
            )
        return self._client

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None

    def __enter__(self) -> "HttpClient":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    # --- Récupération ---

    def get(self, url: str, *, refresh: bool | None = None) -> FetchResult:
        """Récupère une URL (cache disque par défaut) et renvoie son contenu texte."""
        use_refresh = self.refresh if refresh is None else refresh
        cache_path = self._cache_path(url)

        if not use_refresh and cache_path.exists():
            logger.debug("cache → %s", url)
            return FetchResult(url=url, status_code=200, text=cache_path.read_text(encoding="utf-8"), from_cache=True)

        last_error: Exception | None = None
        for attempt in range(1, self.retries + 1):
            try:
                response = self.client.get(url)
                response.raise_for_status()

                cache_path.write_text(response.text, encoding="utf-8")
                time.sleep(self.delay)  # politesse entre deux appels réseau

                return FetchResult(url=url, status_code=response.status_code, text=response.text)
            except httpx.HTTPStatusError as error:
                if error.response.status_code < 500:
                    raise  # 4xx : inutile de réessayer
                last_error = error
            except httpx.HTTPError as error:
                last_error = error

            backoff = 2 ** attempt
            logger.warning("échec (%s/%s) sur %s — nouvel essai dans %ss", attempt, self.retries, url, backoff)
            time.sleep(backoff)

        raise RuntimeError(f"Impossible de récupérer {url} après {self.retries} tentatives : {last_error}")

    def _cache_path(self, url: str) -> Path:
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
        slug = "".join(char if char.isalnum() else "-" for char in url.split("//")[-1])[:60]
        suffix = ".json" if "api.netdeck.gg" in url else ".html"
        return self.cache_dir / f"{slug}-{digest}{suffix}"
