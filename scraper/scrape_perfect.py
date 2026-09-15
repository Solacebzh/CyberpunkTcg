import os
import re
import json
import time
from playwright.sync_api import sync_playwright

DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

# Charger la liste des cartes pour matcher les noms avec les IDs
CARDS_JSON = "output/cards.json"
cards_catalog = []
if os.path.exists(CARDS_JSON):
    with open(CARDS_JSON, "r", encoding="utf-8") as f:
        cards_catalog = json.load(f)

def clean_str(s):
    return re.sub(r'[^a-zA-Z0-9]', '', s).lower() if s else ""

def find_card_id(alt_text, src_url):
    clean_alt = clean_str(alt_text)
    for c in cards_catalog:
        if clean_str(c.get("name")) == clean_alt:
            return c["id"]
        # Vérification si l'URL partielle correspond
        if c.get("imageUrl") and c["imageUrl"].split("?")[0] in src_url:
            return c["id"]
    # Fallback sur un slug du texte alt
    slug = re.sub(r'[^a-z0-9\s-]', '', alt_text.lower()).strip()
    return re.sub(r'[\s_]+', '-', slug) if slug else None

def main():
    print("🚀 Démarrage du navigateur pour extraction mémoire des cartes...")

    saved_cards = set()

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False)
        context = browser.new_context(viewport={"width": 1400, "height": 1000})
        page = context.new_page()

        # Interception directe des réponses HTTP de Chromium
        def on_response(response):
            if "cloudfront.net" in response.url and response.status == 200:
                try:
                    # Ne traiter que les images de rendu
                    if "render-" in response.url or "portal" in response.url:
                        # Chercher l'élément img correspondant sur la page
                        img_bytes = response.body()
                        clean_url = response.url.split("?")[0]
                        
                        # Trouver la carte correspondante
                        matching_id = None
                        for c in cards_catalog:
                            if c.get("imageUrl") and c["imageUrl"].split("?")[0] in clean_url:
                                matching_id = c["id"]
                                break

                        if matching_id and matching_id not in saved_cards:
                            out_path = os.path.join(DEST_DIR, f"{matching_id}.png")
                            with open(out_path, "wb") as f:
                                f.write(img_bytes)
                            print(f" ✨ [Interception Réseau] {matching_id}.png")
                            saved_cards.add(matching_id)
                except Exception:
                    pass

        page.on("response", on_response)

        print("🌐 Ouverture de https://cyberpunktcg.com/cards ...")
        page.goto("https://cyberpunktcg.com/cards", wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(3000)

        # Défilement complet de la page pour tout afficher
        print("📜 Parcours de la galerie...")
        for step in range(40):
            page.mouse.wheel(0, 800)
            page.wait_for_timeout(400)
            
            # Clic automatique sur pagination ou "Load More" si disponible
            for selector in ["button:has-text('Load More')", "button:has-text('More')", "button:has-text('Next')", "a:has-text('Next')"]:
                try:
                    btn = page.locator(selector).first
                    if btn.is_visible():
                        btn.click()
                        page.wait_for_timeout(800)
                except:
                    pass

        page.wait_for_timeout(2000)

        # Deuxième passe : capture directe d'éléments pour les cartes manquantes
        print("\n📸 Capture visuelle directe des cartes affichées...")
        img_elements = page.locator("img[src*='cloudfront.net']").all()
        print(f"🔍 {len(img_elements)} balises images trouvées à l'écran.")

        for img in img_elements:
            try:
                src = img.get_attribute("src") or ""
                alt = img.get_attribute("alt") or ""
                card_id = find_card_id(alt, src)

                if card_id and card_id not in saved_cards:
                    out_path = os.path.join(DEST_DIR, f"{card_id}.png")
                    img.scroll_into_view_if_needed()
                    img.screenshot(path=out_path)
                    print(f" 📸 [Capture DOM] {alt} -> {card_id}.png")
                    saved_cards.add(card_id)
            except Exception as e:
                pass

        print(f"\n🎉 BILAN FINAL : {len(saved_cards)} images enregistrées dans backend/src/main/resources/static/images/cards/")
        browser.close()

if __name__ == "__main__":
    main()
