"""Scraper du Cyberpunk TCG : récupère, normalise et exporte les cartes en JSON.

Pipeline :

    fetch  →  parse  →  models (validation)  →  export (JSON / JSONL / manifeste)

Le backend consomme ensuite le fichier produit par `export` ; le client web
reçoit les cartes depuis l'API, jamais directement depuis le scraper.
"""

__version__ = "0.1.0"
__all__ = ["__version__"]
