# =============================================================================
# Cyberpunk TCG Online — raccourcis de développement
#   make help
# =============================================================================
SHELL := /bin/bash
.DEFAULT_GOAL := help

BACKEND_DIR  := backend
FRONTEND_DIR := frontend
SCRAPER_DIR  := scraper

.PHONY: help install install-backend install-frontend install-scraper \
        db-up db-down db-reset db-logs \
        backend frontend scraper-test test build clean

help: ## Affiche cette aide
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

## --- Installation -----------------------------------------------------------

install: install-backend install-frontend install-scraper ## Installe toutes les dépendances

install-backend: ## Télécharge les dépendances Maven du backend
	cd $(BACKEND_DIR) && mvn -B dependency:go-offline

install-frontend: ## Installe les dépendances npm du frontend
	cd $(FRONTEND_DIR) && npm install

install-scraper: ## Crée le venv Python, installe et enregistre le paquet du scraper
	cd $(SCRAPER_DIR) && python3 -m venv .venv \
		&& .venv/bin/pip install -r requirements.txt \
		&& .venv/bin/pip install -e .

## --- Base de données --------------------------------------------------------

db-up: ## Démarre PostgreSQL (Docker)
	docker compose up -d db

db-down: ## Arrête PostgreSQL
	docker compose down

db-reset: ## Supprime et recrée la base (⚠️ efface les données)
	docker compose down -v && docker compose up -d db

db-logs: ## Suit les logs PostgreSQL
	docker compose logs -f db

## --- Développement ----------------------------------------------------------

backend: ## Lance le backend Spring Boot (http://localhost:8080)
	cd $(BACKEND_DIR) && mvn spring-boot:run

frontend: ## Lance le serveur de dev Vite (http://localhost:5173)
	cd $(FRONTEND_DIR) && npm run dev

## --- Qualité ----------------------------------------------------------------

test: ## Lance les tests backend + scraper
	cd $(BACKEND_DIR) && mvn -B test
	cd $(SCRAPER_DIR) && .venv/bin/python -m pytest -q

build: ## Build de production (jar backend + bundle frontend)
	cd $(BACKEND_DIR) && mvn -B clean package
	cd $(FRONTEND_DIR) && npm run build

clean: ## Nettoie les artefacts de build
	cd $(BACKEND_DIR) && mvn -B clean
	rm -rf $(FRONTEND_DIR)/dist $(FRONTEND_DIR)/node_modules
