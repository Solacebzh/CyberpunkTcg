import os
import json
import asyncio
from playwright.async_api import async_playwright

JSON_FILE = "output/cards.json"
DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

async def download_images():
    with open(JSON_FILE, "r", encoding="utf-8") as f:
        cards = json.load(f)

    downloaded = set()

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        context = await browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        )
        page = await context.new_page()

        async def handle_response(response):
            if "cloudfront.net" in response.url and response.status == 200:
                base_url = response.url.split("?")[0]
                matching = next((c for c in cards if c.get("imageUrl", "").split("?")[0] == base_url), None)
                if matching and matching["id"] not in downloaded:
                    filepath = os.path.join(DEST_DIR, f"{matching['id']}.png")
                    if not os.path.exists(filepath):
                        img_bytes = await response.body()
                        with open(filepath, "wb") as f:
                            f.write(img_bytes)
                        print(f"✅ {matching['id']}.png")
                        downloaded.add(matching["id"])

        page.on("response", handle_response)

        print("🌐 Ouverture du site pour récupérer les cookies & URLs signées...")
        await page.goto("https://cyberpunktcg.com/cards", wait_until="networkidle", timeout=60000)
        
        print("📜 Scroll pour charger les images lazy-loaded...")
        for _ in range(15):
            await page.mouse.wheel(0, 1500)
            await asyncio.sleep(0.4)

        await asyncio.sleep(3)
        print(f"\n📊 Terminé. {len(downloaded)}/{len(cards)} images récupérées.")
        await browser.close()

asyncio.run(download_images())
