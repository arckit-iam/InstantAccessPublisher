# Instant Access Provider

Instant Access Provider est une plateforme qui collecte, normalise et diffuse en temps réel les événements liés à la gestion des identités et des accès (IAM) provenant de différentes sources (Active Directory, applications, LDAP, Azure AD, etc.), et orchestre le provisioning automatique des droits dans les systèmes cibles.

## À quoi ça sert ?
- Centraliser tous les événements d'identité et d'accès dans un format commun ARC (Agnostic Resources Centralizer).
- Faciliter la détection d'anomalies de sécurité (SOC, SIEM).
- Synchroniser automatiquement les changements d'identités vers d'autres systèmes (ITSM, RH, Cloud, etc.).
- Provisionner automatiquement les accès dans les systèmes cibles (AD, RACF, applications).

## Pour qui ?
- Les équipes sécurité (SOC, RSSI) pour surveiller et réagir rapidement aux incidents.
- Les administrateurs IAM pour automatiser la gestion des droits et des accès.
- Les intégrateurs qui veulent connecter facilement de nouvelles sources ou cibles d'identités.

## Points forts
- Compatible avec de nombreuses sources (AD, LDAP, applications, cloud…)
- Modèle de données unifié et extensible (Identity / Entitlement / Assignment)
- Publication des événements sur un bus de messages (Kafka)
- Pensé pour l'automatisation, la sécurité et l'évolutivité

---

## Microservices

| Composant | Rôle |
|---|---|
| `arc-consolidator` | Consomme les événements bruts (`iam.raw.*`), les normalise et publie sur `arc.sync.*` |
| `arc-publisher` | Orchestre les jobs de provisioning et publie les commandes sur `arc.provisioning.*` |
| `arc-agent-{cible}` | Consomme `arc.provisioning.*` et exécute les opérations sur le système cible |
| `profile` (product-manager) | Service métier de gestion des profils et permissions par produit. Appelle `arc-publisher` (`POST /jobs`) pour créer ou supprimer le groupe AD associé à chaque profil |

---

## Convention de nommage des topics Kafka

### `iam.raw.*` — Événements bruts entrants (sources → arc-consolidator)

| Topic | Source |
|---|---|
| `iam.raw.activedirectory` | Active Directory |
| `iam.raw.racf` | RACF |
| `iam.raw.{source}` | Toute autre source interne |
| `iam.raw.external.{vendor}` | Sources SaaS externes (ex : `iam.raw.external.salesforce`) |

### `arc.sync.*` — Événements consolidés (arc-consolidator → consommateurs)

| Topic | Domaine | Opérations |
|---|---|---|
| `arc.sync.identity` | Identités | CREATE / MODIFY / DELETE |
| `arc.sync.assignment` | Affectations de droits | CREATE / DELETE |
| `arc.sync.entitlement` | Droits / habilitations | CREATE / MODIFY / DELETE |

Ces topics représentent la **vue normalisée et fiable** du référentiel IAM, indépendamment de la source.

### `arc.provisioning.*` — Commandes de provisioning (arc-publisher → arc-agent-*)

| Topic | Domaine | Opérations |
|---|---|---|
| `arc.provisioning.identity` | Provisioning d'identités | PROVISION / DEPROVISION |
| `arc.provisioning.assignment` | Provisioning d'affectations | ASSIGN / UNASSIGN |
| `arc.provisioning.entitlement` | Provisioning de droits | CREATE / DELETE |

Chaque événement contient un champ `targetSystem` (ex : `ad://corp.example.com`, `racf://RACF1`) qui permet à chaque agent de filtrer les messages qui le concernent.

---

## Architecture cible

```
Sources IAM (AD, RACF, ...)
        |  iam.raw.{source}
        v
  arc-consolidator  -->  arc.sync.identity
                    -->  arc.sync.assignment
                    -->  arc.sync.entitlement

Sources SaaS (Salesforce, ...)
        |  iam.raw.external.{vendor}
        v
  arc-consolidator (adapter dédié par vendor)

arc-publisher  -->  arc.provisioning.identity
               -->  arc.provisioning.assignment
               -->  arc.provisioning.entitlement
                              |
            +-----------------+-----------------+
            v                 v                 v
      arc-agent-ad      arc-agent-racf      arc-agent-{app}
    (filtre ad://)     (filtre racf://)    (filtre app://)
```

### arc-agent-{cible}

Un **arc-agent** est un composant autonome déployé côté système cible. Il :
- Consomme les topics `arc.provisioning.*` dans son propre consumer group
- Filtre les événements par `targetSystem` (schéma de l'URI)
- Exécute les opérations sur le système cible (création de compte, affectation de groupe, etc.)
- Est livré clé-en-main et configurable (ex : `arc-agent-ad` pour Active Directory)

Les équipes qui intègrent un nouveau système implémentent l'interface `ProvisioningHandlerPort` fournie par `arc-agent-sdk`.

### Intégration SaaS externe (futur)

Les applications SaaS qui souhaitent publier leurs données IAM :
- Publient sur `iam.raw.external.{vendor}` dans leur format natif
- arc-consolidator embarque un adapter de normalisation dédié
- Le format de sortie reste identique (`arc.sync.*`)

---

## Documentation

- [arc-docs](https://github.com/arckit-iam/arc-docs) : architecture, modèle abstrait IAM, collecte, SOC.
- [arc-kubeorchestrator](https://github.com/arckit-iam/arc-kubeorchestrator/tree/main/docs) : déploiement, Helm, Flux et CI/CD.

## Licence

Copyright © 2026 Dédale Solutions

Ce projet est distribué sous la licence [EUPL-1.2](LICENSE) (European Union Public Licence v. 1.2). La documentation (gent/docs/) est sous licence CC-BY-4.0.
