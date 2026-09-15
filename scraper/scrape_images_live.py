import os
import re
import time
import json
import urllib.request
from playwright.sync_api import sync_playwright

DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

# Charger notre catalogue pour matcher les IDs exacts du backend
CARDS_JSON = "output/cards.json"
id_map = {}
if os.path.exists(CARDS_JSON):
    with open(CARDS_JSON, "r", encoding="utf-8") as f:
        cards_data = json.load(f)
        for c in cards_data:
            # Nettoyage pour correspondance insensible à la casse et ponctuation
            clean_name = re.sub(r'[^a-zA-Z0-9]', '', c['name']).lower()
            id_map[clean_name] = c['id']

def slugify(text):
    text = text.lower()
    text = re.sub(r'[^a-z0-9\s-]', '', text)
    text = re.sub(r'[\s_]+', '-', text)
    return text.strip('-')

def main():
    print("🚀 Lancement du navigateur pour récupérer les 151 images signées...")
    
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False) # Mode visible pour voir le scroll
        context = browser.new_context(viewport={"width": 1400, "height": 900})
        page = context.new_page()

        print("🌐 Chargement de https://cyberpunktcg.com/cards ...")
        page.goto("https://cyberpunktcg.com/cards", timeout=60000)
        page.wait_for_timeout(3000)

        # Faire défiler toute la page pour déclencher le lazy-loading de toutes les cartes
        print("📜 Défilement automatique pour charger toutes les cartes...")
        last_height = 0
        for i in range(30):
            page.evaluate("window.scrollBy(0, 1200)")
            page.wait_for_timeout(500)
            
            # Clic sur un éventuel bouton "Load More" ou "Afficher plus" si présent
            try:
                load_more = page.locator("button:has-text('Load More'), button:has-text('Show More'), button:has-text('More')")
                if load_more.is_visible():
                    load_more.click()
                    page.wait_for_timeout(1000)
            except:
                pass

        page.wait_for_timeout(2000)

        # Récupération de toutes les balises <img> avec CloudFront
        images = page.eval_on_selector_all(
            "img[src*='cloudfront.net']",
            "elements => elements.map(e => ({ src: e.src, alt: e.alt || '' }))"
        )

        print(f"\n📦 {len(images)} images trouvées sur la page !")

        # Dédoublonnage
        unique_images = {}
        for img in images:
            src = img['src']
            alt = img['alt']
            if src and "Signature=" in src:
                unique_images[src] = alt

        print(f"🎯 {len(unique_images)} images uniques prêtes au téléchargement.\n")

        success = 0
        for i, (src, alt) in enumerate(unique_images.items(), 1):
            clean_alt = re.sub(r'[^a-zA-Z0-9]', '', alt).lower()
            card_id = id_map.get(clean_alt, slugify(alt) if alt else f"card-{i}")
            
            dest_file = os.path.join(DEST_DIR, f"{card_id}.png")

            print(f"[{i:03d}/{len(unique_images)}] ⬇️ {alt} -> {card_id}.png ...", end="", flush=True)

            try:
                # Téléchargement direct avec l'URL signée complète
                req = urllib.request.Request(
                    src,
                    headers={"User-Agent": "Mozilla/5.0"}
                )
                with urllib.request.urlopen(req, timeout=15) as response, open(dest_file, "wb") as out_file:
                    out_file.write(response.read())
                print(" OK ! ✨")
                success += 1
            except Exception as e:
                print(f" ERREUR ({e}) ❌")

        print(f"\n🎉 TERMINÉ ! {success}/{len(unique_images)} images enregistrées dans backend !")
        browser.close()

if __name__ == "__main__":
    main()
