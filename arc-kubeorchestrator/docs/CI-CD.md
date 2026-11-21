# CI/CD — Pipeline de build, promotion et déploiement

## Vue d'ensemble

Le pipeline suit un modèle **éditeur logiciel** :
packages immuables, promotions explicites, déploiement séparé du build.

```
git tag v1.2.0
  └── [CI] Build + Tests + Package → iap-agent-1.2.0-dev

GitHub Pre-release
  └── [CI] Promote dev → rc → iap-agent-1.2.0-rc
      └── [CD] Deploy prod-lab

GitHub Release
  └── [CI] Promote rc → stable → iap-agent-1.2.0
      └── [CD] Deploy prod-lab
```

---

## Étapes détaillées

### 1. Tag → Build `-dev`

**Déclencheur** : `git push --tags` (tag format `vX.Y.Z`)

**Ce que le pipeline fait** :
1. Checkout du code
2. Compilation Java 21
3. Exécution des tests (66 tests)
4. Packaging → `iap-agent-X.Y.Z-dev.jar`
5. Publication de l'artifact dans le registry (GitHub Packages / Artifactory)

**Ce qu'il ne fait PAS** : pas de déploiement, pas de promotion.

```
Artifact produit :
  packages/dev/iap-agent-1.2.0-dev.jar
```

---

### 2. Pre-release → Promote `-rc` + Deploy

**Déclencheur** : création d'une **Pre-release** GitHub sur le tag `vX.Y.Z`

**Ce que le pipeline fait** :
1. Récupère `iap-agent-X.Y.Z-dev.jar` du registry
2. Le copie / renomme → `iap-agent-X.Y.Z-rc.jar` (**même binaire, nouveau label**)
3. Publie dans `packages/rc/`
4. Déploie sur `prod-lab`

```
Artifact produit :
  packages/rc/iap-agent-1.2.0-rc.jar

Déploiement :
  → prod-lab (WEC01-lab)
```

---

### 3. Release → Promote stable + Deploy

**Déclencheur** : passage en **Release** (depuis Pre-release) dans GitHub

**Ce que le pipeline fait** :
1. Récupère `iap-agent-X.Y.Z-rc.jar` du registry
2. Le copie / renomme → `iap-agent-X.Y.Z.jar` (**même binaire, label stable**)
3. Publie dans `packages/release/`
4. Déploie sur `prod-lab`

```
Artifact produit :
  packages/release/iap-agent-1.2.0.jar

Déploiement :
  → prod-lab (WEC01-lab)
```

---

## Structure des artifacts

```
packages/
  dev/
    iap-agent-1.2.0-dev.jar       ← build CI, non validé
  rc/
    iap-agent-1.2.0-rc.jar        ← candidat à la release, déployé sur lab
  release/
    iap-agent-1.2.0.jar           ← livrable client, stable
```

---

## Workflows GitHub Actions

### `.github/workflows/build.yml` — Tag → `-dev`

```yaml
name: Build

on:
  push:
    tags:
      - 'v[0-9]+.[0-9]+.[0-9]+'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'microsoft'
          cache: maven

      - name: Extract version
        id: version
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Build and test
        run: mvn -f agent/pom.xml package --batch-mode

      - name: Rename artifact
        run: |
          mv agent/target/iap-agent-*.jar \
             agent/target/iap-agent-${{ steps.version.outputs.VERSION }}-dev.jar

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: iap-agent-${{ steps.version.outputs.VERSION }}-dev
          path: agent/target/iap-agent-${{ steps.version.outputs.VERSION }}-dev.jar
          retention-days: 30
```

---

### `.github/workflows/promote.yml` — Pre-release (dev→rc) + Release (rc→stable) + appel deploy

```yaml
name: Promote

on:
  release:
    types: [prereleased, released]

jobs:
  promote:
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.version.outputs.VERSION }}
    steps:
      # Pre-release uniquement : dev → rc
      - name: Download dev artifact
        if: github.event.action == 'prereleased'
        ...

      # Release uniquement : rc → stable + attach sur la GitHub Release
      - name: Download rc artifact
        if: github.event.action == 'released'
        ...

  deploy:
    needs: promote
    if: github.event.action == 'released'        # deploy uniquement sur release
    uses: ./.github/workflows/deploy.yml
    with:
      version: ${{ needs.promote.outputs.version }}
    secrets: inherit
```

---

### `.github/workflows/deploy.yml` — Workflow réutilisable (`workflow_call`)

Appelé par `promote.yml` via `uses:`. Conçu pour être appelé plus tard aussi
par un projet `iap-integrator` dédié au déploiement client.

```yaml
name: Deploy

on:
  workflow_call:
    inputs:
      version:
        required: true
        type: string

jobs:
  deploy:
    environment: prod-lab
    steps:
      - download artifact
      - scp jar vers prod-lab
      - ssh Restart-Service IapAgent
```

> **Note** : `deploy.yml` n'a pas de déclencheur direct (`push`, `release`…).
> Il ne peut être invoqué que via `workflow_call` — ce qui garantit qu'il
> ne tourne jamais seul en dehors d'un pipeline contrôlé.
> Plus tard, `iap-integrator` l'appellera de la même façon avec sa propre config.

```yaml
name: Promote RC + Deploy

on:
  release:
    types: [prereleased]

jobs:
  promote:
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.version.outputs.VERSION }}
    steps:
      - name: Extract version
        id: version
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Download dev artifact
        uses: actions/download-artifact@v4
        with:
          name: iap-agent-${{ steps.version.outputs.VERSION }}-dev

      - name: Promote dev → rc
        run: |
          mv iap-agent-${{ steps.version.outputs.VERSION }}-dev.jar \
             iap-agent-${{ steps.version.outputs.VERSION }}-rc.jar

      - name: Upload rc artifact
        uses: actions/upload-artifact@v4
        with:
          name: iap-agent-${{ steps.version.outputs.VERSION }}-rc
          path: iap-agent-${{ steps.version.outputs.VERSION }}-rc.jar
          retention-days: 90

  deploy:
    needs: promote
    runs-on: ubuntu-latest
    environment: prod-lab
    steps:
      - name: Download rc artifact
        uses: actions/download-artifact@v4
        with:
          name: iap-agent-${{ needs.promote.outputs.version }}-rc

      - name: Deploy to prod-lab
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.PRODLAB_HOST }}
          username: ${{ secrets.PRODLAB_USER }}
          key: ${{ secrets.PRODLAB_SSH_KEY }}
          source: "iap-agent-${{ needs.promote.outputs.version }}-rc.jar"
          target: ${{ secrets.PRODLAB_DEPLOY_PATH }}

      - name: Restart agent service
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.PRODLAB_HOST }}
          username: ${{ secrets.PRODLAB_USER }}
          key: ${{ secrets.PRODLAB_SSH_KEY }}
          script: |
            cp ${{ secrets.PRODLAB_DEPLOY_PATH }}/iap-agent-*.jar \
               ${{ secrets.PRODLAB_DEPLOY_PATH }}/iap-agent.jar
            Restart-Service IapAgent
```

---

### `.github/workflows/release.yml` — Release → stable + Deploy

```yaml
name: Release + Deploy

on:
  release:
    types: [released]

jobs:
  promote:
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.version.outputs.VERSION }}
    steps:
      - name: Extract version
        id: version
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Download rc artifact
        uses: actions/download-artifact@v4
        with:
          name: iap-agent-${{ steps.version.outputs.VERSION }}-rc

      - name: Promote rc → stable
        run: |
          mv iap-agent-${{ steps.version.outputs.VERSION }}-rc.jar \
             iap-agent-${{ steps.version.outputs.VERSION }}.jar

      - name: Upload stable artifact
        uses: actions/upload-artifact@v4
        with:
          name: iap-agent-${{ steps.version.outputs.VERSION }}
          path: iap-agent-${{ steps.version.outputs.VERSION }}.jar
          retention-days: 365

      - name: Attach to GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: iap-agent-${{ steps.version.outputs.VERSION }}.jar

  deploy:
    needs: promote
    runs-on: ubuntu-latest
    environment: prod-lab
    steps:
      - name: Download stable artifact
        uses: actions/download-artifact@v4
        with:
          name: iap-agent-${{ needs.promote.outputs.version }}

      - name: Deploy to prod-lab
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.PRODLAB_HOST }}
          username: ${{ secrets.PRODLAB_USER }}
          key: ${{ secrets.PRODLAB_SSH_KEY }}
          source: "iap-agent-${{ needs.promote.outputs.version }}.jar"
          target: ${{ secrets.PRODLAB_DEPLOY_PATH }}

      - name: Restart agent service
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.PRODLAB_HOST }}
          username: ${{ secrets.PRODLAB_USER }}
          key: ${{ secrets.PRODLAB_SSH_KEY }}
          script: |
            cp ${{ secrets.PRODLAB_DEPLOY_PATH }}/iap-agent-*.jar \
               ${{ secrets.PRODLAB_DEPLOY_PATH }}/iap-agent.jar
            Restart-Service IapAgent
```

---

## Secrets GitHub requis

À configurer dans **Settings → Secrets and variables → Actions** du repository.

### Secrets d'environnement (`prod-lab`)

| Secret | Description | Exemple |
|---|---|---|
| `PRODLAB_HOST` | Hostname ou IP du serveur WEC de lab | `wec01-lab.iap.local` |
| `PRODLAB_USER` | Utilisateur SSH (ou WinRM) pour le déploiement | `svc-deploy` |
| `PRODLAB_SSH_KEY` | Clé privée SSH (RSA ou Ed25519, sans passphrase) | *(clé PEM complète)* |
| `PRODLAB_DEPLOY_PATH` | Répertoire de déploiement sur le serveur | `C:\iap` |

### Variables d'environnement de l'agent sur prod-lab

Ces valeurs sont configurées directement sur le serveur (dans le service Windows NSSM
ou dans `C:\iap\.env`), **pas dans GitHub Secrets** :

| Variable | Valeur prod-lab |
|---|---|
| `IAP_INSTANCE_ID` | `agent-wec01-lab` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka-lab.iap.local:9092` |
| `IAP_AGENT_ADAPTERS_WEF_ENABLED` | `true` |
| `IAP_WEF_SOURCE_ID` | `ad://iap.local` |
| `IAP_WEF_REMOTE_HOST` | *(vide — agent tourne sur WEC01)* |
| `IAP_CONFIG_FILE` | `file:C:\iap\rules.yml` |
| `IAP_LOG_LEVEL` | `INFO` |

---

## Procédure de release complète (pas à pas)

```bash
# 1. Se mettre sur main à jour
git checkout main && git pull

# 2. Bumper la version dans pom.xml
# Editer agent/pom.xml : <version>1.2.0</version> (retirer -SNAPSHOT)

# 3. Commit + tag
git add agent/pom.xml
git commit -m "release: v1.2.0"
git tag v1.2.0
git push origin main --tags
# → CI déclenche le build → iap-agent-1.2.0-dev.jar

# 4. Créer une Pre-release dans GitHub
# GitHub → Releases → Draft a new release → Tag: v1.2.0
# ☑ Set as pre-release → Publish
# → CI promote dev→rc + deploy prod-lab

# 5. Valider sur prod-lab (smoke test, vérification Kafka, logs)

# 6. Passer en Release
# GitHub → Releases → v1.2.0 → Edit → Uncheck "pre-release" → Update
# → CI promote rc→stable + deploy prod-lab + attach jar sur la release GitHub
```

---

## Pourquoi ce modèle ?

| Propriété | Bénéfice |
|---|---|
| **Build une seule fois** | Le binaire livré au client est identique à celui testé en lab |
| **Packages immuables** | Pas de re-build entre les promotions — reproductibilité garantie |
| **Séparation Build / Deploy** | On peut déployer sans rebuilder, et rebuilder sans déployer |
| **Artifact distribué au client** | Le `.jar` stable est attaché à la GitHub Release — livrable direct |
| **Environments GitHub** | Approval manuel possible avant deploy prod (règles de protection) |
