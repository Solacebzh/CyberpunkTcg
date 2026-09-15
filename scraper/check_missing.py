import os, json

DEST_DIR = "../backend/src/main/resources/static/images/cards"
with open("output/cards.json", "r", encoding="utf-8") as f:
    cards = json.load(f)

have = {f[:-4] for f in os.listdir(DEST_DIR) if f.endswith(".png")}
missing = [c for c in cards if c["id"] not in have]

print(f"✅ Présentes : {len(have)}")
print(f"❌ Manquantes : {len(missing)}\n")
for c in missing:
    print(f"  - {c['id']}")

with open("missing.json", "w", encoding="utf-8") as f:
    json.dump([c["id"] for c in missing], f, indent=2)
