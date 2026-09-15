import os
import json
import time
import re
from playwright.sync_api import sync_playwright

DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

with open("output/cards.json", "r", encoding="utf-8") as f:
    all_cards = json.load(f)

# Identifier les cartes encore manquantes sur le disque
have = {f[:-4] for f in os.listdir(DEST_DIR) if f.endswith(".png")}
missing_cards = [c for c in all_cards if c["id"] not in have]

print(f"🎯 Cartes restantes à chercher : {len(missing_cards)}")

url_to_id = {}
for c in all_cards:
    u = c.get("imageUrl", "")
    if u and u.startswith("http"):
        url_to_id[u.split("?")[0]] = c["id"]

def main():
    if not missing_cards:
        print("🎉 Aucune carte manquante !")
        return

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False)
        context = browser.new_context(viewport={"width": 1400, "height": 900})
        page = context.new_page()

        def on_response(response):
            if "cloudfront.net" not in response.url or response.status != 200:
                return
            base = response.url.split("?")[0]
            cid = url_to_id.get(base)
            if cid and cid not in have:
                out_path = os.path.join(DEST_DIR, f"{cid}.png")
                try:
                    with open(out_path, "wb") as f:
                        f.write(response.body())
                    have.add(cid)
                    print(f" ✨ [Interception] {cid}.png")
                except Exception:
                    pass

        page.on("response", on_response)

        print("🌐 Ouverture de https://cyberpunktcg.com/cards ...")
        page.goto("https://cyberpunktcg.com/cards", wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(3000)

        # Trouver le champ de recherche
        search_input = None
        selectors = [
            "input[placeholder*='Search' i]",
            "input[placeholder*='card' i]",
            "input[type='search']",
            "input[type='text']",
            "input"
        ]
        for sel in selectors:
            loc = page.locator(sel).first
            if loc.is_visible(timeout=1000):
                search_input = loc
                print(f"🔍 Barre de recherche trouvée ({sel}) !")
                break

        for i, card in enumerate(missing_cards, 1):
            cid = card["id"]
            name = card["name"]

            if cid in have:
                continue

            print(f"[{i:02d}/{len(missing_cards)}] 🔎 Recherche : '{name}'...", end="", flush=True)

            if search_input:
                try:
                    search_input.fill("")
                    search_input.fill(name)
                    search_input.press("Enter")
                    page.wait_for_timeout(1500)
                except Exception as e:
                    print(f" (Erreur input: {e})", end="")

            # Fallback capture DOM si l'interception réseau n'a pas déclenché
            if cid not in have:
                try:
                    img = page.locator("img[src*='cloudfront.net']").first
                    if img.is_visible(timeout=1000):
                        out_path = os.path.join(DEST_DIR, f"{cid}.png")
                        img.screenshot(path=out_path)
                        have.add(cid)
                        print(" OK (Capture) 📸")
                    else:
                        print(" Non trouvé ❌")
                except Exception:
                    print(" Non trouvé ❌")
            else:
                print(" OK ✨")

        print(f"\n🎉 BILAN FINAL : {len(have)}/{len(all_cards)} images dans le backend !")
        browser.close()

if __name__ == "__main__":
    main()
