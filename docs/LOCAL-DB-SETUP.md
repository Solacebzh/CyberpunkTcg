# Installer la base locale sous Windows (PostgreSQL 16 + DBeaver)

Ce guide prépare PostgreSQL pour le backend Spring Boot de **Cyberpunk TCG**. Les commandes PowerShell sont à lancer depuis la racine du dépôt, sauf indication contraire.

## 1. Installer PostgreSQL 16

1. Ouvrir <https://www.postgresql.org/download/windows/> puis choisir **Download the installer** (EDB).
2. Télécharger PostgreSQL **16.x** pour Windows x86-64 et lancer l'installateur.
3. Conserver les composants **PostgreSQL Server**, **Command Line Tools** et, au choix, pgAdmin. Stack Builder n'est pas requis.
4. Conserver le dossier proposé et le port **5432**.
5. Définir le mot de passe de l'utilisateur administrateur `postgres`. Pour correspondre aux valeurs de développement par défaut du projet, utiliser `postgres` (uniquement sur une machine locale). Sinon, noter le mot de passe choisi et configurer `DB_PASS` plus bas.
6. Conserver la locale proposée, terminer l'installation, puis vérifier dans `services.msc` que le service `postgresql-x64-16` est démarré.

Vérification dans un nouveau PowerShell (adapter le chemin si nécessaire) :

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" --version
```

Si le port 5432 est déjà pris, choisir un autre port lors de l'installation et le reporter dans l'URL JDBC (par exemple `5433`).

## 2. Créer la base `cyberpunk_tcg`

### Avec SQL Shell (`psql`)

1. Ouvrir **SQL Shell (psql)** depuis le menu Démarrer.
2. Accepter `localhost`, la base `postgres`, le port `5432` et l'utilisateur `postgres` avec Entrée, puis saisir le mot de passe.
3. Exécuter :

```sql
CREATE DATABASE cyberpunk_tcg
  WITH ENCODING 'UTF8'
  TEMPLATE template0;
```

4. Vérifier la connexion :

```sql
\connect cyberpunk_tcg
SELECT current_database(), current_user;
\quit
```

La création des tables est automatique au premier démarrage en profil `dev` (`spring.jpa.hibernate.ddl-auto=update`). Il n'est pas nécessaire de créer les tables à la main.

Alternative en une commande PowerShell :

```powershell
& "C:\Program Files\PostgreSQL\16\bin\createdb.exe" -h localhost -p 5432 -U postgres -E UTF8 cyberpunk_tcg
```

## 3. Installer et utiliser DBeaver

1. Télécharger **DBeaver Community** depuis <https://dbeaver.io/download/> (Windows Installer).
2. Installer puis lancer DBeaver.
3. Cliquer **Nouvelle connexion** (icône prise), sélectionner **PostgreSQL**, puis **Suivant**.
4. Renseigner :
   - Host : `localhost`
   - Port : `5432`
   - Database : `cyberpunk_tcg`
   - Username : `postgres`
   - Password : celui choisi à l'installation (`postgres` avec les valeurs par défaut)
5. Cliquer **Test Connection**. Accepter le téléchargement du pilote PostgreSQL proposé par DBeaver, puis refaire le test.
6. Cliquer **Terminer**. Dans le navigateur de bases, ouvrir `cyberpunk_tcg` → `Schemas` → `public` → `Tables`.

Pour exécuter du SQL : clic droit sur la connexion → **SQL Editor** → **New SQL Script**, saisir par exemple `SELECT current_database();`, puis `Ctrl+Entrée`. Après le premier lancement du backend, utiliser **F5 / Refresh** sur `Tables` pour afficher les tables générées.

DBeaver est un client graphique : il ne remplace pas le serveur PostgreSQL, qui doit rester démarré.

## 4. Configurer le backend

### Option A — valeurs par défaut (la plus simple)

Le profil Spring `dev` utilise, en l'absence de variables :

```text
DATABASE_URL=jdbc:postgresql://localhost:5432/cyberpunk_tcg
DB_USER=postgres
DB_PASS=postgres
```

Si PostgreSQL utilise exactement ces paramètres, aucune variable n'est nécessaire. Depuis `backend` :

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Si le dépôt ne contient pas le wrapper Maven, utiliser Maven installé :

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Option B — variables pour la session PowerShell

À utiliser si le mot de passe, l'utilisateur, l'hôte ou le port diffère :

```powershell
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/cyberpunk_tcg"
$env:DB_USER = "postgres"
$env:DB_PASS = "votre-mot-de-passe"
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Ces variables disparaissent à la fermeture du terminal. Attention : `DATABASE_URL` est une **URL JDBC**, elle commence donc par `jdbc:postgresql://`.

### Option C — variables Windows persistantes

```powershell
[Environment]::SetEnvironmentVariable("DATABASE_URL", "jdbc:postgresql://localhost:5432/cyberpunk_tcg", "User")
[Environment]::SetEnvironmentVariable("DB_USER", "postgres", "User")
[Environment]::SetEnvironmentVariable("DB_PASS", "votre-mot-de-passe", "User")
```

Fermer et rouvrir PowerShell/IDE après ces commandes. Ne jamais committer de mot de passe dans Git. Pour supprimer une variable persistante, lui affecter `$null` avec la même commande.

## 5. Contrôle final et dépannage

Une fois le backend lancé, vérifier l'absence d'erreur de connexion dans sa console et rafraîchir les tables dans DBeaver. Le frontend peut ensuite être lancé depuis `frontend` avec `npm install` puis `npm run dev`.

Erreurs courantes :

- **Connection refused** : le service PostgreSQL est arrêté, ou le port de `DATABASE_URL` est incorrect.
- **password authentication failed** : `DB_USER` / `DB_PASS` ne correspondent pas au compte PostgreSQL.
- **database does not exist** : vérifier l'orthographe exacte `cyberpunk_tcg` et recréer la base.
- **Port 5432 already in use** : arrêter l'ancien serveur ou employer son port réel dans PostgreSQL, DBeaver et `DATABASE_URL`.
- **Profil incorrect / base en mémoire** : vérifier que le backend est lancé avec le profil `dev`.

> Variante Docker : le dépôt fournit aussi `docker-compose.yml`. `docker compose up -d db` crée une base avec l'utilisateur/mot de passe `cyberpunk` par défaut ; dans ce cas configurer `DB_USER=cyberpunk` et `DB_PASS=cyberpunk`.
