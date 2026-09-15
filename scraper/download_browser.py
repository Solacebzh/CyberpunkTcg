import os
import json
import asyncio
from playwright.async_api import async_playwright

JSON_FILE = "output/cards.json"
DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

async def download_via_browser_context():
    with open(JSON_FILE, "r", encoding="utf-8") as f:
        cards = json.load(f)

    async with async_playwright() as p:
        # On ouvre un vrai navigateur Chrome visible
        browser = await p.chromium.launch(headless=False)
        context = await browser.new_context()
        page = await context.new_page()

        print("🌐 Connexion à cyberpunktcg.com pour initialiser les cookies...")
        await page.goto("https://cyberpunktcg.com", wait_until="domcontentloaded", timeout=60000)
        await asyncio.sleep(2)

        print(f"📥 Début du téléchargement de {len(cards)} images...")
        success = 0
        fail = 0

        for i, card in enumerate(cards, 1):
            card_id = card.get("id")
            url = card.get("imageUrl")
            if not url or not url.startswith("http"):
                continue

            out_path = os.path.join(DEST_DIR, f"{card_id}.png")
            if os.path.exists(out_path) and os.path.getsize(out_path) > 1000:
                success += 1
                continue

            print(f"[{i:03d}/{len(cards)}] ⬇️ {card_id}...", end="", flush=True)

            try:
                # On demande au navigateur de faire la requête lui-même avec ses cookies
                base64_data = await page.evaluate("""async (imageUrl) => {
                    const res = await fetch(imageUrl);
                    if (!res.ok) return null;
                    const blob = await res.blob();
                    return new Promise((resolve) => {
                        const reader = new FileReader();
                        reader.onloadend = () => resolve(reader.result.split(',')[1]);
                        reader.readAsDataURL(blob);
                    });
                }""", url)

                if base64_data:
                    import base64
                    with open(out_path, "wb") as f:
                        f.write(base64.b64decode(base64_data))
                    print(" OK ! ✨")
                    success += 1
                else:
                    print(" ÉCHEC ❌")
                    fail += 1
            except Exception as e:
                print(f" ERREUR ({e}) ❌")
                fail += 1

            await asyncio.sleep(0.05)

        print(f"\n📊 Bilan : {success} réussies, {fail} échecs.")
        await browser.close()

asyncio.run(download_via_browser_context())
