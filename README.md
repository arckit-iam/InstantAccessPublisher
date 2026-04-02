# Instant Access Provider : Dossier d'Architecture — Plateforme IAM Event Streaming

## Objectif

Ce dossier décrit l'architecture d'une plateforme **générique** qui collecte en temps réel les événements de gestion d'identités et d'accès depuis **n'importe quelle source** (Active Directory, applications métier, LDAP, Azure AD…), les normalise en un modèle abstrait commun, et les **publie dans un bus de messages** (topic).

Deux familles de consommateurs sont adressées :

- **SOC** (Security Operations Center) — détection d'anomalies, alertes, corrélation SIEM.
- **Synchronisation événementielle** — propagation des changements d'identités vers d'autres systèmes (IAM, ITSM, RH, Cloud IdP…).

> **Principe fondateur** : un groupe de sécurité AD, un profil applicatif, un groupOfNames LDAP, un rôle AWS IAM sont tous la même entité abstraite : un **Entitlement**. La plateforme ne travaille qu'avec des `Identity`, des `Entitlement` et des `Assignment`.

---

## Structure du dossier

| Fichier | Contenu |
|---|---|
| `docs/00-modele-abstrait.md` | **Fondations** : modèle générique Identity / Entitlement / Assignment |
| `docs/01-contexte-objectifs.md` | Contexte métier, parties prenantes, périmètre |
| `docs/02-architecture-globale.md` | Vue d'ensemble, schéma de flux, composants |
| `docs/03-collecte-ad.md` | Mécanismes de collecte des logs AD (WEF, Winlogbeat, DirSync) |
| `docs/04-traitement-detection.md` | Pipeline de traitement, normalisation, détection des événements métier |
| `docs/05-publication-messaging.md` | Choix du broker, topologie des topics `iam.*`, schémas CloudEvents |
| `docs/06-soc-securite.md` | Cas d'usage SOC, règles de détection, intégration SIEM |
| `docs/07-synchronisation.md` | Synchronisation événementielle, pattern Outbox, idempotence, replay |
| `docs/08-choix-techno.md` | Comparatif technologique (Kafka vs Event Hub vs RabbitMQ…) |
| `docs/09-securite-architecture.md` | Hardening, secrets, RBAC, chiffrement, audit de la plateforme |
| `docs/10-roadmap.md` | Extensions futures : Entra ID, MFA events, UEBA |
| `docs/11-adapter-sources.md` | **Extensibilité** : AD Adapter, App Adapter (webhook/CDC/polling), LDAP Adapter |

---

## Vue macro (résumé)

```
┌──────────────────────────────────────────────────────────────────────┐
│                        SOURCES (extensibles)                         │
│  ┌────────────┐  ┌─────────────────┐  ┌───────────┐  ┌──────────┐  │
│  │ Active Dir │  │  Application A  │  │   LDAP    │  │ Azure AD │  │
│  │  (AD)      │  │  (profils appli)│  │ OpenLDAP  │  │ (v2)     │  │
│  └─────┬──────┘  └────────┬────────┘  └─────┬─────┘  └────┬─────┘  │
└────────╪─────────────────╪─────────────────╪─────────────╪─────────┘
         │                  │                 │             │
         ▼                  ▼                 ▼             ▼
┌──────────────────────────────────────────────────────────────────────┐
│              ADAPTERS (1 par source, modèle générique)               │
│         AD Adapter    App Adapter    LDAP Adapter    Entra Adapter   │
│               ↓ Traduisent en Identity / Entitlement / Assignment    │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ iam.raw.{source}
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│               PIPELINE (Normalisation + Détection)                   │
│         CloudEvents iam.* → Enrichissement → Règles SOC              │
└──────────┬───────────────────┬─────────────────────┬─────────────────┘
           │                   │                     │
           ▼                   ▼                     ▼
  ┌────────────────┐  ┌────────────────┐  ┌──────────────────────┐
  │  iam.alert     │  │  iam.identity  │  │  iam.entitlement     │
  │  (SOC)         │  │  iam.assignment│  │  (groupes, profils)  │
  └───────┬────────┘  └───────┬────────┘  └──────────┬───────────┘
          │                   │                       │
          ▼                   └───────────┬───────────┘
  ┌───────────────┐                       ▼
  │  SIEM / SOAR  │           ┌──────────────────────┐
  │  (Splunk,     │           │  IAM / IdP / ITSM    │
  │   Elastic,    │           │  (Okta, ServiceNow,  │
  │   QRadar)     │           │   Système RH maison) │
  └───────────────┘           └──────────────────────┘
```

---

## Les neuf événements génériques

Quelle que soit la source (AD, application, LDAP…), la plateforme ne produit que **9 types d'événements** :

| Type CloudEvent | Abstraction | Équivalent AD | Équivalent Application |
|---|---|---|---|
| `iam.identity.created` | Identity créée | Event 4720 | Création compte applicatif |
| `iam.identity.updated` | Identity modifiée | Event 4738 | Modification profil utilisateur |
| `iam.identity.enabled` | Identity activée | Event 4722 | Activation compte |
| `iam.identity.disabled` | Identity désactivée | Event 4725 | Désactivation compte |
| `iam.identity.deleted` | Identity supprimée | Event 4726 | Suppression compte |
| `iam.entitlement.created` | Entitlement créé | Event 4727/4731/4754 | Création profil applicatif |
| `iam.entitlement.updated` | Entitlement modifié | Modif. attributs groupe | Modif. profil applicatif |
| `iam.entitlement.deleted` | Entitlement supprimé | Event 4730/4734/4758 | Suppression profil |
| `iam.assignment.added` | Assignment créé | Event 4728/4732/4756 | Affectation User → Profil |
| `iam.assignment.removed` | Assignment retiré | Event 4729/4733/4757 | Retrait User → Profil |

---

## Prérequis de lecture

- Connaissance de base d'Active Directory et des Windows Event Logs
- Notions de messaging asynchrone (Kafka, Event Hub, etc.)
- Familiarité avec les patterns event-driven (Event Sourcing, CQRS)
