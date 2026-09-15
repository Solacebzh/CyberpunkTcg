import os
import json
from playwright.sync_api import sync_playwright

DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

with open("output/cards.json", "r", encoding="utf-8") as f:
    cards = json.load(f)

# Index URL de base -> id
url_to_id = {}
for c in cards:
    u = c.get("imageUrl", "")
    if u and u.startswith("http"):
        url_to_id[u.split("?")[0]] = c["id"]

have = {f[:-4] for f in os.listdir(DEST_DIR) if f.endswith(".png")}
total = len(cards)
print(f"📊 Déjà présentes : {len(have)}/{total}")
print(f"🎯 À récupérer : {total - len(have)}\n")

saved = set(have)

def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False)
        page = browser.new_context(viewport={"width": 1500, "height": 1000}).new_page()

        def on_response(response):
            if "cloudfront.net" not in response.url or response.status != 200:
                return
            base = response.url.split("?")[0]
            cid = url_to_id.get(base)
            if cid and cid not in saved:
                try:
                    with open(os.path.join(DEST_DIR, f"{cid}.png"), "wb") as fh:
                        fh.write(response.body())
                    saved.add(cid)
                    print(f" ✨ [{len(saved)}/{total}] {cid}.png")
                except Exception:
                    pass

        page.on("response", on_response)

        print("🌐 Chargement de la galerie...")
        page.goto("https://cyberpunktcg.com/cards", wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(3000)

        # Remonter tout en haut
        page.evaluate("window.scrollTo(0, 0)")
        page.wait_for_timeout(1500)

        print("📜 Défilement progressif (haut -> bas)...\n")
        last_count = -1
        stagnant = 0

        for i in range(200):
            if len(saved) >= total:
                print("\n🏁 Toutes les cartes sont récupérées !")
                break

            page.evaluate("window.scrollBy(0, 600)")
            page.wait_for_timeout(350)

            # Bouton "load more" éventuel
            if i % 10 == 0:
                for sel in ["button:has-text('Load More')", "button:has-text('Show more')"]:
                    try:
                        btn = page.locator(sel).first
                        if btn.is_visible(timeout=300):
                            btn.click()
                            page.wait_for_timeout(1200)
                    except Exception:
                        pass

            # Détection de fin de page
            if i % 15 == 0:
                if len(saved) == last_count:
                    stagnant += 1
                    at_bottom = page.evaluate(
                        "window.innerHeight + window.scrollY >= document.body.scrollHeight - 200"
                    )
                    if at_bottom and stagnant >= 3:
                        print("\n⬇️ Bas de page atteint, plus rien à charger.")
                        break
                else:
                    stagnant = 0
                last_count = len(saved)

        page.wait_for_timeout(2000)
        print(f"\n🎉 BILAN : {len(saved)}/{total} images dans le backend.")
        missing = [c["id"] for c in cards if c["id"] not in saved]
        if missing:
            print(f"\n❌ Toujours manquantes ({len(missing)}) :")
            for m in missing[:30]:
                print(f"  - {m}")
        browser.close()

main()
