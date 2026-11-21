# Instant Access Provider

Instant Access Provider (IAP) est une plateforme open source qui collecte, normalise et diffuse en temps réel les événements d'identité et d'accès (IAM) provenant de différentes sources (Active Directory, RACF, applications, LDAP...), et orchestre le provisioning automatique des droits dans les systèmes cibles.

> **Statut : projet en développement actif.** L'architecture et les contrats de messages (topics Kafka, payloads) peuvent encore évoluer. Ne pas considérer l'ensemble comme prêt pour la production sans évaluation préalable.

## À quoi ça sert ?

- Centraliser les événements d'identité et d'accès dans un format commun ARC (Agnostic Resources Centralizer).
- Faciliter la détection d'anomalies de sécurité (SOC, SIEM).
- Synchroniser les changements d'identités vers d'autres systèmes (ITSM, RH, Cloud...).
- Provisionner les accès dans les systèmes cibles (AD, RACF, applications).

## Pour qui ?

- Les équipes sécurité (SOC, RSSI) pour surveiller et réagir aux incidents.
- Les administrateurs IAM pour automatiser la gestion des droits.
- Les intégrateurs qui veulent connecter de nouvelles sources ou cibles d'identités.

## Points forts

- Modèle de données unifié et extensible (Identity / Entitlement / Assignment).
- Bus de messages Kafka (compatible Redpanda).
- Architecture hexagonale : chaque source ou cible s'ajoute par un adapter.
- Déploiement Kubernetes (Helm, FluxCD).

---

## Dépôts

| Dépôt | Rôle |
|---|---|
| [`InstantAccessPublisher`](https://github.com/arckit-iam/InstantAccessPublisher) (ce dépôt) | Vitrine, vue d'ensemble, agent collecteur (`agent/`) |
| [`arc-consolidator`](https://github.com/arckit-iam/arc-consolidator) | Consomme les événements bruts (`iam.raw.*`), les normalise et publie sur `arc.sync.*` |
| [`arc-publisher`](https://github.com/arckit-iam/arc-publisher) | Planifie les jobs de provisioning (REST + PostgreSQL) et publie les commandes sur `arc.provisioning.*` |
| [`arc-agent-ad`](https://github.com/arckit-iam/arc-agent-ad) | Consomme `arc.provisioning.*` et exécute les opérations dans Active Directory |
| [`product-manager`](https://github.com/arckit-iam/product-manager) (dossier `profile`) | Service métier : produits, profils et permissions. Appelle `arc-publisher` pour créer ou supprimer le groupe AD associé à un profil |
| [`arc-docs`](https://github.com/arckit-iam/arc-docs) | Documentation d'architecture, modèle abstrait, collecte, SOC |

Le dossier [`agent/`](agent/) contient l'agent collecteur : il lit les événements à la source (WEF, DirSync/LDAP, webhook, syslog) et les publie sur `iam.raw.*`.

---

## Flux de données

```text
Sources IAM (AD, RACF, ...)
        |  agent collecteur
        v
   iam.raw.{source}
        |
        v
  arc-consolidator  -->  arc.sync.identity | arc.sync.assignment | arc.sync.entitlement

product-manager --POST /jobs--> arc-publisher --> arc.provisioning.identity
                                              --> arc.provisioning.assignment
                                              --> arc.provisioning.entitlement
                                                         |
                                       +-----------------+-----------------+
                                       v                 v                 v
                                 arc-agent-ad     arc-agent-racf    arc-agent-{app}
                                (ad://...)        (racf://...)       (app://...)
```

## Convention de nommage des topics Kafka

### `iam.raw.*` : événements bruts entrants

| Topic | Source |
|---|---|
| `iam.raw.activedirectory` | Active Directory |
| `iam.raw.racf` | RACF |
| `iam.raw.{source}` | Toute autre source interne |
| `iam.raw.external.{vendor}` | Sources SaaS externes (prévu) |

### `arc.sync.*` : événements consolidés (arc-consolidator vers consommateurs)

| Topic | Domaine |
|---|---|
| `arc.sync.identity` | Identités |
| `arc.sync.assignment` | Affectations de droits |
| `arc.sync.entitlement` | Droits / habilitations |

Ces topics représentent la vue normalisée du référentiel IAM, indépendamment de la source.

### `arc.provisioning.*` : commandes de provisioning (arc-publisher vers arc-agent-*)

| Topic | Domaine |
|---|---|
| `arc.provisioning.identity` | Provisioning d'identités |
| `arc.provisioning.assignment` | Provisioning d'affectations |
| `arc.provisioning.entitlement` | Provisioning de droits |

Les actions portées par un job sont `CREATE`, `MODIFY` et `DELETE`. Chaque événement contient un champ `system` (ex : `ad://corp.example.com`) qui permet à chaque agent de filtrer les messages qui le concernent.

## arc-agent-{cible}

Un agent est un composant autonome déployé côté système cible. Il consomme les topics `arc.provisioning.*` dans son propre consumer group, filtre par système cible et exécute les opérations (création de groupe, affectation, etc.). Un nouvel agent implémente un port de traitement des commandes ; [`arc-agent-ad`](https://github.com/arckit-iam/arc-agent-ad) (`ProvisioningHandlerPort`) sert d'implémentation de référence.

---

## Démarrage rapide

Un `docker compose` de démonstration ([`demo/`](demo/)) lance toute la chaîne en local : Redpanda, PostgreSQL, `arc-consolidator`, `arc-publisher` et `arc-agent-ad`. L'agent tourne en mode **dry-run** : il journalise les opérations sans toucher à un Active Directory, aucun AD n'est donc nécessaire.

Prérequis : Docker avec Compose v2. Les trois services sont construits depuis leurs dépôts GitHub publics (le premier démarrage prend quelques minutes).

```bash
# 1. Démarrer la pile
docker compose -f demo/docker-compose.yml up -d --build

# 2. Injecter un événement brut Active Directory (création de compte, EventID 4720)
docker compose -f demo/docker-compose.yml exec -T redpanda \
  rpk topic produce iam.raw.activedirectory < demo/ad-event.jsonl

# 3. Lire l'événement normalisé produit par arc-consolidator
docker compose -f demo/docker-compose.yml exec -T redpanda \
  rpk topic consume arc.sync.identity -o start -n 1

# 4. Créer un job de provisioning (API arc-publisher, locale uniquement)
curl -X POST http://127.0.0.1:8081/jobs -H 'Content-Type: application/json' -d '{
  "action": "CREATE",
  "targetType": "IDENTITY",
  "identityId": "jdoe",
  "identityName": "John Doe",
  "targetSystem": "ad://AD01",
  "executeAt": "2020-01-01T00:00:00Z"
}'

# 5. Constater l'exécution par l'agent (lignes [DRY-RUN])
docker compose -f demo/docker-compose.yml logs arc-agent-ad | grep DRY-RUN

# 6. Tout arrêter et supprimer les données
docker compose -f demo/docker-compose.yml down -v
```

Sous PowerShell, remplacer l'étape 2 par `Get-Content demo/ad-event.jsonl | docker compose -f demo/docker-compose.yml exec -T redpanda rpk topic produce iam.raw.activedirectory`.

> Cette démonstration est destinée à un usage local : l'API n'est pas authentifiée (elle n'écoute que sur `127.0.0.1`), les identifiants sont fictifs et rien n'est durci pour la production.

---

## Intégration et déploiement

Le déploiement GitOps (Helm, FluxCD) est géré dans un dépôt privé. L'intégration et la mise en production de la plateforme sont proposées par Dédale Solutions en **service payant** : pour en discuter, contactez-nous à [etiennsalle@gmail.com](mailto:etiennsalle@gmail.com).

---

## Documentation

- [arc-docs](https://github.com/arckit-iam/arc-docs) : architecture, modèle abstrait IAM, collecte, SOC.
- Chaque dépôt contient son propre README (configuration, build, tests).

## Contribuer

Les contributions sont les bienvenues : voir [CONTRIBUTING.md](CONTRIBUTING.md). Pour signaler une vulnérabilité, voir [SECURITY.md](SECURITY.md).

## Licence

Copyright © 2026 Dédale Solutions

Le code est distribué sous la licence [EUPL-1.2](LICENSE) (European Union Public Licence v. 1.2). La documentation (`agent/docs/`) est sous licence CC-BY-4.0. La conformité est vérifiable avec [REUSE](https://reuse.software).
