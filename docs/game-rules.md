# Règles de jeu retenues

Ce document fixe les règles utilisées par le futur moteur autoritaire. Les trois arbitrages auparavant
incertains sont désormais **confirmés** : victoire à **7 Gigs**, **une seule vente par tour** et réactions
réservées aux cartes **QUICK**. Ils ne doivent plus être traités comme des options de configuration.

## 1. Zones et ressources

| Élément | Rôle |
| --- | --- |
| **Fixer Area** | Contient au départ les 6 dés Gig du joueur (`d4`, `d6`, `d8`, `d10`, `d12`, `d20`). |
| **Gig Area** | Contient les Gigs contrôlés, y compris ceux volés au rival. |
| **Eddies Area** | Chaque carte vendue face cachée vaut 1 Eddie ; la carte est révélée avant d'y être posée. |
| **Legends Area** | Les 3 Legends du joueur commencent face cachée. Une Legend face cachée peut être **inclinée** (dépensée) pour gagner 1 Eddie : c'est la réserve d'Eddies du joueur, et l'inclinaison est définitive (une Legend inclinée n'est jamais redressée). |
| **Field** | Units et cartes qui leur sont équipées. |
| **Trash** | Cartes défaussées, vaincues ou résolues. |
| **Street Cred** | Somme des valeurs visibles des dés dans la Gig Area. |
| **RAM** | Limite de construction de deck, calculée par couleur à partir des Legends. |

## 2. Construction de deck

- exactement **3 Legends**, avec des noms uniques, hors du deck principal ;
- **40 à 50 cartes** dans le deck principal ;
- au maximum **3 copies** d'une même carte ;
- les RAM des Legends s'additionnent par couleur ; une carte est légale si sa couleur est couverte et si
  sa RAM ne dépasse pas le plafond correspondant.

Exemple : deux Legends à 2 RAM vert et une Legend à 2 RAM rouge autorisent les cartes vertes jusqu'à
4 RAM et rouges jusqu'à 2 RAM.

## 3. Mise en place

1. mélanger les 3 Legends et les poser face cachée ;
2. placer les 6 dés Gig dans la Fixer Area ;
3. mélanger le deck principal et piocher 6 cartes ;
4. chaque joueur peut effectuer au plus un mulligan (non implémenté en V1 : limite assumée, voir
   `RULE-ENGINE.md` §11) ;
5. déterminer le premier joueur : le moteur le **tire au sort** ; le premier joueur subit le malus de mise
   en place et commence avec **2 Legends déjà inclinées** (il ne peut donc obtenir qu'1 Eddie en inclinant
   sa troisième Legend).

## 4. Tour de jeu

### Phase de début

1. vérifier la victoire avant de prendre un nouveau Gig ;
2. piocher une carte (ne pas pouvoir piocher fait perdre la partie) ;
3. choisir et lancer un dé de la Fixer Area, puis le placer dans la Gig Area ; le `d20` doit rester le dernier ;
4. redresser les cartes qui doivent être prêtes.

### Phase principale

Le joueur actif peut jouer des cartes, activer des effets et attaquer dans l'ordre autorisé par les règles.

**Vente confirmée : une seule fois par tour.** Le joueur peut vendre une carte de sa main portant le tag de
vente. Elle est révélée puis placée face cachée dans l'Eddies Area et vaut exactement 1 Eddie, quel que soit
son coût imprimé. Une deuxième vente pendant le même tour est illégale.

**Eddies : incliner une Legend.** Une Legend face cachée de la Legends Area peut être inclinée à tout moment
pour +1 Eddie (définitif). Les Eddies ainsi obtenus, comme ceux des ventes, sont dépensés pour payer les
coûts : `coût payé = max(0, coût imprimé − remise)`. Le Street Cred est un seuil : il n'est pas consommé
lorsqu'un effet vérifie sa valeur.

**RAM en partie.** Le plafond de RAM par couleur (somme des RAM des Legends) est vérifié à la pose d'une
carte : une carte dont la RAM imprimée dépasse le plafond de sa couleur est illégale. La RAM n'est jamais
dépensée — plusieurs cartes à la RAM maximale restent jouables dans le même tour.

## 5. Attaque, combat et vol

1. une Unit prête est dépensée pour déclarer une cible légale ;
2. les déclencheurs d'attaque sont placés en résolution ;
3. une fenêtre de réaction est ouverte ;
4. le combat compare les puissances totales ; à égalité, les deux Units perdent le combat ;
5. une attaque valide de la Gig Area permet de voler un Gig selon les règles de résolution.

Une Unit jouée ce tour ne peut normalement pas attaquer. Un effet tel que `go_solo` peut explicitement
lever cette restriction. Un Gear suit la carte à laquelle il est équipé quand celle-ci change de zone.

## 6. Réactions : QUICK uniquement

Pendant une fenêtre de réaction, le défenseur ne peut jouer comme réaction qu'une carte ou un effet portant
le mot-clé **`quick`**. Une carte sans QUICK reste soumise aux timings normaux, même si son texte serait utile
à cet instant. Les déclencheurs obligatoires déjà créés par le jeu ne deviennent pas pour autant des cartes
« jouées en réaction ».

Cette contrainte doit être vérifiée côté serveur ; le client peut masquer une action illégale mais n'est jamais
la source de vérité.

## 7. Victoire et défaite

| Condition | Résultat |
| --- | --- |
| Le joueur **commence son tour avec au moins 7 Gigs** dans sa Gig Area | victoire immédiate, avant le nouveau Gig du tour |
| Le joueur doit piocher mais son deck est vide | défaite |

Chaque joueur commence avec 6 dés : atteindre 7 Gigs exige donc normalement d'en voler au rival ou d'utiliser
un effet de carte. Le seuil n'est pas 6.

## 8. Mots-clés représentés dans le schéma

| Valeur JSON | Usage |
| --- | --- |
| `go_solo` | Permet l'attaque le tour où la carte est jouée lorsque le texte le prévoit. |
| `blocker` | Intervient dans la désignation/redirection des défenseurs. |
| `quick` | Seul timing autorisant une carte à être jouée pendant une fenêtre de réaction. |
| `flip` | Effet lié au retournement d'une carte. |
| `play` | Effet déclenché quand la carte est jouée. |
| `attack` | Effet déclenché à la déclaration d'une attaque. |

Le scraper détecte ces mots-clés dans le champ API et dans le balisage du texte (`{Quick}`, `{Play}`, etc.).

## 9. Invariants pour le moteur

- le serveur décide de la légalité de chaque action ;
- `gigsToWin = 7` ;
- `salesPerTurn = 1` ;
- une action dans la fenêtre de réaction exige `quick` ;
- les limites RAM sont calculées par couleur lors de la validation du deck **et** vérifiées à la pose ;
- les Eddies proviennent des Legends inclinées (+1, définitif) et de la vente unique du tour (+1) ;
- les tirages et lancers utilisent une graine journalisée pour permettre les replays déterministes.
