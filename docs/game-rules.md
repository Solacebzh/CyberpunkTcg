# Règles du Cyberpunk TCG — référence pour le moteur

Résumé de travail des règles officielles, dans la forme dont le moteur a besoin.
⚠️ Le jeu est sorti en **Beta** : plusieurs points sont encore mouvants (marqués « à confirmer »).
Le moteur est conçu pour que ces valeurs soient **des données** (JSON) et non du code, afin d'absorber
les ajustements sans réécriture.

Sources : site officiel (`cyberpunktcg.com`), guides « How to play » et FAQ éditeurs (voir `roadmap.md`).

## 1. Matériel et zones

| Zone | Rôle |
| --- | --- |
| **Fixer Area** | Les 6 dés **Gig** au début de la partie (d4, d6, d8, d10, d12, d20) |
| **Legend Area** | Les **3 Legends**, posées face cachée en début de partie |
| **Field** | Les Units, Programs en cours de résolution et Gear attachés |
| **Eddies** | Les cartes vendues pour générer la ressource |
| **Trash** | Units détruites, Programs résolus, Gear détachés |
| **Gig Area** | Les dés Gig gagnés (le tableau de score) — une aire par joueur |

## 2. Construction de deck

| Règle | Valeur |
| --- | --- |
| Legends | **exactement 3**, noms différents, hors deck principal |
| Deck principal | **40 à 50 cartes** |
| Copies maximum | **3** exemplaires d'une carte de même nom |
| Limite de main | aucune |
| Contrainte de couleur (RAM) | les **RAM cumulées** des 3 Legends plafonnent la RAM jouable **par couleur** |

Exemple officiel : Goro Takemura (2 RAM vert) + Saburo Arasaka (2 RAM vert) + Yorinobu Arasaka (2 RAM rouge)
⇒ cartes **vertes jusqu'à 4 RAM** et **rouges jusqu'à 2 RAM**.
Une carte ne peut entrer dans le deck que si sa couleur est couverte par une Legend et si sa RAM est
inférieure ou égale au plafond cumulé de cette couleur.

## 3. Mise en place

1. Mélanger ses 3 Legends et les poser **face cachée**.
2. Placer les 6 dés Gig dans la **Fixer Area**.
3. Mélanger le deck principal, **piocher 6 cartes**.
4. Un **mulligan** autorisé, une seule fois, sans pénalité.
5. Déterminer qui commence (à trancher : lance de dé ou choix joueur 1 → feature 04).

## 4. Déroulement d'un tour

| Phase | Actions |
| --- | --- |
| **Ready** | Piocher 1 carte ; lancer **1 dé Gig** pour obtenir du **Street Cred** (valeur du dé = Street Cred du tour) ; redresser (« ready ») toutes ses cartes tournées |
| **Play** | Vendre une carte de sa main pour ses Eddies *(à confirmer : une fois par tour ou librement)* ; retourner une Legend face visible en payant son coût ; jouer des Units / Programs / Gear en payant en Eddies |
| **Attack** | Dépenser des Units pour attaquer : cible = Unit adverse (combat) ou aire de Gigs adverse (vol de Gig) |

Règles transverses :

- **Eddies** : n'importe quelle carte de la main peut être vendue pour sa valeur en Eddies ;
  c'est la ressource qui paie Units, Programs, Gear et le retournement des Legends.
- **Street Cred** : sert de seuil — une carte avec un coût de Street Cred ne peut être jouée
  que si le Street Cred du tour est atteint.
- **Spend / Ready** : une carte dépensée (tournée à 90°) ne peut plus agir avant la phase Ready suivante.

## 5. Combat

```
Attaquant déclare une Unit et une cible
  1. Étape offensive   : l'Unit attaquante se dépense, ses capacités ATTACK se déclenchent
  2. Étape défensive   : le défenseur peut retourner une Legend face visible (FLIP)
                         et/ou rediriger l'attaque vers une Unit avec BLOCKER
                         (une fenêtre de réaction « React window » existe en Beta : QUICK)
  3. Fight             : comparer la Puissance totale des deux Units
                         - plus forte → gagne, l'autre est détruite
                         - égalité    → les deux sont détruites
  4. Fin               : les Units détruites et le Gear attaché vont au Trash ;
                         l'Unit survivante « se connecte » sur sa cible
```

- Attaquer directement l'**aire de Gigs** adverse permet de **voler un dé Gig** (transfert d'un dé de
  l'adversaire vers son propre Gig Area). Une **Puissance ≥ 10** peut voler des Gigs supplémentaires *(à confirmer)*.
- Une Unit ne peut attaquer que si elle est prête (**ready**), sauf keyword **GO SOLO**.
- Le **Gear** reste attaché à son Unit et part au Trash si celle-ci quitte le Field.

## 6. Conditions de victoire

| Condition | Détail |
| --- | --- |
| **Victoire principale** | Débuter **son tour** avec **6 dés Gig** (ou plus) dans son Gig Area *(certaines sources Beta annoncent 7 : à confirmer)* |
| **Défaite par épuisement** | Ne pas pouvoir piocher dans un deck vide |

Le décompte se fait donc **au début du tour**, pas en cours de résolution.

## 7. Mots-clés

| Keyword | Effet |
| --- | --- |
| `go_solo` | L'Unit peut attaquer le tour même où elle est jouée (équivalent « hâte ») |
| `blocker` | Seule une Unit BLOCKER peut rediriger une attaque vers elle |
| `quick` | Jouable par le défenseur pendant la fenêtre de réaction |
| `flip` | Effet déclenché quand la carte (généralement une Legend) est retournée face visible |
| `play` | Effet déclenché à la pose de la carte |
| `attack` | Effet déclenché quand l'Unit attaque (avant la comparaison de Puissance) |

Les mots-clés sont détectés automatiquement par le scraper à partir du texte de la carte
(`normalize.py` → `normalize_keywords`) ; c'est la liste `keywords` du schéma qui fait foi pour le moteur.

## 8. Ce que le moteur doit implémenter (feature 05)

| # | Règle | Complexité |
| --- | --- | --- |
| R1 | Validation des decks (3 Legends uniques, 40-50 cartes, 3 copies max, plafonds de RAM par couleur) | facile |
| R2 | Mise en place, pioche, mulligan, main cachée, main sans limite | facile |
| R3 | Phase Ready (pioche + lancer de dé Gig + redressement) | facile |
| R4 | Économie : vente de carte → Eddies, coûts, Street Cred | moyen |
| R5 | Pose de cartes, ciblage, Gear attaché, Legends face cachée puis FLIP | moyen |
| R6 | Combat complet (dépense, BLOCKER, comparaison, égalités, Trash, fuite du Gear) | moyen |
| R7 | Vol de Gigs, seuil de victoire en début de tour | moyen |
| R8 | Fenêtre de réaction (QUICK) et pile de résolution des effets | difficile |
| R9 | Effets de cartes textuels (mots-clés + un petit langage d'effets data-driven) | difficile |
| R10 | Rejeu déterministe (graine RNG journalisée) | facile une fois R1-R9 stables |

### Points à confirmer au fil des sorties

- nombre exact de Gigs pour gagner (6 ou 7) ;
- vente de cartes : une fois par tour ou autant de fois que voulu ;
- détail complet de la fenêtre de réaction (QUICK) et de ses interactions ;
- gestion exacte des effets continus (« jusqu'à la fin du tour ») et des effets simultanés.

**Stratégie retenue** : ces paramètres seront dans `config/game-rules.json` (valeurs par défaut versionnées),
chargés par le moteur — un aller-retour avec les amis pour ajuster une règle ne demandera donc pas de redéploiement.

### Périmètre volontairement hors V1

- effets de cartes exotiques/blessures non documentés ;
- mode plus de 2 joueurs ;
- gestion des Errata et de la légalité par extension (banlist).
