import os
import re
import json
import requests
import time

# Configuration des dossiers
DEST_DIR = "../backend/src/main/resources/static/images/cards"
os.makedirs(DEST_DIR, exist_ok=True)

# En-têtes pour simuler un vrai navigateur et contourner la sécurité CloudFront (403 Forbidden)
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://cyberpunktcg.com/",
    "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
    "Accept-Language": "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"
}

def slugify(text):
    """Convertit le nom de la carte en ID correspondant au frontend"""
    text = text.lower()
    text = re.sub(r'[^a-z0-9\s-]', '', text)
    text = re.sub(r'[\s_]+', '-', text)
    return text.strip('-')

def download_images():
    print("🛰️ Récupération des données originales des cartes depuis NetDeck...")
    api_url = "https://api.netdeck.gg/api/cards/cyberpunk?limit=200"
    
    try:
        response = requests.get(api_url, headers=HEADERS, timeout=10)
        response.raise_for_status()
        data = response.json()
    except Exception as e:
        print(f"❌ Impossible de se connecter à l'API : {e}")
        return

    # L'API peut renvoyer directement une liste ou un objet contenant une liste
    cards = data if isinstance(data, list) else data.get("cards", data.get("data", []))
    print(f"✅ {len(cards)} cartes trouvées. Début du téléchargement des images...")

    success_count = 0
    fail_count = 0

    for i, card in enumerate(cards, 1):
        name = card.get("name")
        card_id = slugify(name)
        
        # Récupération de l'URL de l'image (les clés peuvent varier)
        img_url = card.get("image") or card.get("imageUrl") or card.get("image_url")
        
        if not img_url:
            print(f"[{i:03d}/{len(cards)}] ⚠️ Pas d'image pour {name}")
            continue

        dest_path = os.path.join(DEST_DIR, f"{card_id}.png")

        # Évite de retélécharger si l'image existe déjà
        if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
            print(f"[{i:03d}/{len(cards)}] ⏭️ {name} (déjà présente)")
            success_count += 1
            continue

        print(f"[{i:03d}/{len(cards)}] ⬇️ Téléchargement : {name}...", end="", flush=True)

        try:
            # Petite pause pour ne pas surcharger le serveur
            time.sleep(0.2)
            
            img_res = requests.get(img_url, headers=HEADERS, timeout=15)
            if img_res.status_code == 200:
                with open(dest_path, "wb") as f:
                    f.write(img_res.content)
                print(" OK ! ✨")
                success_count += 1
            else:
                print(f" ÉCHEC (Code HTTP {img_res.status_code}) ❌")
                fail_count += 1
        except Exception as e:
            print(f" ÉCHEC (Erreur : {e}) ❌")
            fail_count += 1

    print(f"\n📊 Bilan : {success_count} images téléchargées avec succès, {fail_count} échecs.")

if __name__ == "__main__":
    download_images()
