import os
import json
import requests
import time

# Fichiers et dossiers
JSON_SOURCE = "output/cards.json"
DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

# En-têtes pour contourner la sécurité CloudFront (403 Forbidden)
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://cyberpunktcg.com/",
    "Accept": "image/webp,image/apng,image/*,*/*;q=0.8"
}

def download():
    if not os.path.exists(JSON_SOURCE):
        print(f"Erreur : Fichier {JSON_SOURCE} introuvable. Lance d'abord le scraper !")
        return

    with open(JSON_SOURCE, "r", encoding="utf-8") as f:
        cards = json.load(f)

    print(f"✅ {len(cards)} cartes trouvées dans {JSON_SOURCE}. Début du téléchargement...")

    success, fail = 0, 0
    for i, card in enumerate(cards, 1):
        card_id = card.get("id")
        img_url = card.get("imageUrl")
        
        # On ignore les liens locaux ou absents
        if not img_url or not img_url.startswith("http"):
            continue

        dest_path = os.path.join(DEST_DIR, f"{card_id}.png")

        # Ne pas retélécharger si déjà présent
        if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
            success += 1
            continue

        print(f"[{i:03d}/{len(cards)}] ⬇️ {card_id}...", end="", flush=True)
        try:
            time.sleep(0.15) # Petite pause pour ne pas se faire bannir
            res = requests.get(img_url, headers=HEADERS, timeout=15)
            if res.status_code == 200:
                with open(dest_path, "wb") as f:
                    f.write(res.content)
                print(" OK ✨")
                success += 1
            else:
                print(f" ÉCHEC ({res.status_code}) ❌")
                fail += 1
        except Exception as e:
            print(f" ÉCHEC ({e}) ❌")
            fail += 1

    print(f"\n📊 Bilan : {success} images téléchargées, {fail} échecs sur {len(cards)} cartes.")

if __name__ == "__main__":
    download()
