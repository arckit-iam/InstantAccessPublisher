# 02 — Architecture Globale

## 1. Principes directeurs

| Principe | Description |
|---|---|
| **Event-Driven** | Les sources sont la vérité. Tout changement est un événement immuable. |
| **Découplage** | Aucun consommateur n'interroge les sources directement. |
| **Single Source of Truth** | Le topic est le registre des faits, pas une représentation du présent. |
| **Idempotence** | Les consommateurs doivent tolérer un rejeu sans effet de bord. |
| **Defense in Depth** | La plateforme elle-même est sécurisée et auditée. |
| **Haute disponibilité** | Les Agents sont stateless et horizontalement scalables — plusieurs instances en parallèle. |
| **Segmentation** | Un Agent par zone de sécurité (Tier-0, Production, Cloud…) ou par région géographique. |

---

## 2. Vue d'ensemble des couches

```
╔══════════════════════════════════════════════════════════════════════╗
║  COUCHE 1 — SOURCES                                                  ║
║                                                                      ║
║  AD Domain Controllers  ──[WEF / Event Forwarding]──►               ║
║  ISVD / Équipements     ──[Syslog UDP/TCP/TLS]──────►  vers Agent   ║
║  Serveurs Linux         ──[Syslog / auditd]─────────►               ║
║  Applications           ──[Webhook / API REST / CDC]►               ║
║  OpenLDAP / Entra ID    ──[DirSync / Graph API]─────►               ║
╚══════════════════════════════════════════════════════════════════════╝
                        │
╔═══════════════════════▼══════════════════════════════════════════════╗
║  COUCHE 2 — AGENT (Java, stateless, multi-instances)                 ║
║                                                                      ║
║  ┌───────────────────────────────────────────────────────────────┐  ║
║  │                        AGENT                                  │  ║
║  │                                                               │  ║
║  │  ┌─────────────────┐  ┌──────────────┐  ┌─────────────────┐  │  ║
║  │  │ Adapter         │  │ Adapter      │  │ Adapter         │  │  ║
║  │  │ WEF / EventLog  │  │ Syslog       │  │ Webhook / API   │  │  ║
║  │  └────────┬────────┘  └──────┬───────┘  └────────┬────────┘  │  ║
║  │           │                  │                   │            │  ║
║  │           └──────────────────┴───────────────────┘            │  ║
║  │                              │                                │  ║
║  │                    ┌─────────▼─────────┐                      │  ║
║  │                    │   Normalizer      │                      │  ║
║  │                    │  (mapping métier) │                      │  ║
║  │                    └─────────┬─────────┘                      │  ║
║  │                              │                                │  ║
║  │                    ┌─────────▼─────────┐                      │  ║
║  │                    │  Kafka Publisher  │                      │  ║
║  │                    │  (avec retry /    │                      │  ║
║  │                    │   buffer disque)  │                      │  ║
║  │                    └─────────┬─────────┘                      │  ║
║  └──────────────────────────────┼───────────────────────────────┘  ║
║                                 │                                   ║
║  Plusieurs instances en parallèle :                                  ║
║  Agent-1 (Zone Tier-0)  ─────────────────────────────────────────►  ║
║  Agent-2 (Zone Prod)    ─────────────────────────────────────────►  ║
║  Agent-3 (Site London)  ─────────────────────────────────────────►  ║
╚═════════════════════════════════╪════════════════════════════════════╝
                                  │
╔═════════════════════════════════▼════════════════════════════════════╗
║  COUCHE 3 — BROKER / TOPICS KAFKA                                    ║
║                                                                      ║
║   iam.raw.{sourceId}    ← événements bruts par source (interne)     ║
║   iam.identity          ← identités normalisées (public)            ║
║   iam.entitlement       ← habilitations normalisées (public)        ║
║   iam.assignment        ← assignements normalisés (public)          ║
║   iam.alert             ← alertes SOC (public)                      ║
║   iam.audit             ← audit trail 365j (archivage)              ║
║   iam.dlq               ← dead-letter queue (ops)                  ║
╚═════════════════════════════════╪════════════════════════════════════╝
                                  │
         ┌────────────────────────┼──────────────────┐
         ▼                        ▼                  ▼
╔════════════════╗ ╔══════════════════╗ ╔══════════════════════╗
║  SOC           ║ ║  SYNC IAM        ║ ║  AUTRES              ║
║  • Splunk      ║ ║  • Okta / SCIM   ║ ║  • ServiceNow        ║
║  • Elastic     ║ ║  • Azure AD      ║ ║  • Data Warehouse    ║
║  • QRadar      ║ ║  • SailPoint     ║ ║  • Audit Trail       ║
║  • SOAR        ║ ║  • SIRH maison   ║ ║                      ║
╚════════════════╝ ╚══════════════════╝ ╚══════════════════════╝
```

---

## 3. Description des composants

### 3.1 Sources

| Source | Transport vers l'Agent | Notes |
|---|---|---|
| **Active Directory (DCs)** | Windows Event Forwarding (WEF) → WEC Server | Natif Windows, pas d'agent sur les DCs |
| **Active Directory (fallback)** | DirSync LDAP (polling) | Filet de sécurité si Event Logs partiels |
| **ISVD / équipements réseau** | Syslog UDP/TCP/TLS (port 514/6514) | Format RFC 5424 ou CEF |
| **Serveurs Linux** | Syslog (rsyslog/syslog-ng) ou auditd | Vers collecteur Syslog de l'Agent |
| **Applications métier** | Webhook HTTP, polling REST API, CDC DB | Voir §4 de `docs/11-collecteurs.md` |
| **Entra ID / Azure AD** | Microsoft Graph API Delta Queries | Roadmap v2 |

### 3.2 Agent

L'**Agent** est le cœur de la collecte. C'est un **service Java, stateless**, configuré par fichier YAML. Il embarque plusieurs **Adapters** — un par protocole de transport — et un **Normalizer** interne.

**Responsabilités de l'Agent :**
- Chaque **Adapter** écoute un protocole source (WEF, Syslog, Webhook, API…)
- Le **Normalizer** interne mappe les événements bruts vers le modèle IAM abstrait
- Le **Kafka Publisher** publie dans `iam.raw.{sourceId}` avec retry et buffer disque
- **Déduplication** : clé SHA-256 sur (EventID + TargetSID + timestamp tronqué à la seconde)

**Ce que l'Agent ne fait PAS :**
- Il ne prend pas de décision métier (alertes, corrélation) — c'est le Stream Processor
- Il ne stocke pas d'état persistant — il peut être relancé / scalé librement

### 3.3 Haute disponibilité — Multi-instances

L'Agent est conçu pour tourner en **plusieurs instances simultanées** :

```
AD (DCs) ──[WEF]──► Agent-1  ─┐
                  Agent-2  ─┤──► topic: iam.raw.activedirectory
                  Agent-3  ─┘       (Kafka déduplique via clé de partition)
```

| Axe | Pattern |
|---|---|
| **Tolérance aux pannes** | Si Agent-1 tombe, Agent-2 et Agent-3 continuent |
| **Scalabilité géographique** | Agent-EU, Agent-US, Agent-ASIA — chacun collecte sa région |
| **Segmentation sécurité** | Agent dédié par zone : Tier-0, Production, Admin, Cloud |
| **Démarrage** | `agent --config agent-zone-tier0.yml` — même binaire, config différente |

**Segmentation par zone (exemple grand compte) :**
```
Zone Tier-0   → Agent-Tier0   → iam.raw.ad.tier0
Zone Prod     → Agent-Prod    → iam.raw.ad.prod
Zone Cloud    → Agent-Cloud   → iam.raw.entra-id
```
> Les équipes sécurité peuvent ainsi appliquer des politiques d'alerte différentes par zone.

### 3.4 Stream Processor

Responsable de :
- **Consommer** les topics `iam.raw.*` (produits par les Agents)
- **Normaliser** en schéma CloudEvents (si pas déjà fait par le Normalizer de l'Agent)
- **Détecter** les patterns métier (ajout à groupe sensible, hors heures ouvrées, corrélation temporelle…)
- **Enrichir** depuis des sources externes (CMDB, référentiel RH, LDAP)
- **Router** vers les topics publics (`iam.identity`, `iam.entitlement`, `iam.assignment`, `iam.alert`)

### 3.5 Bus de Messages (Kafka)

Kafka sert de **registre de faits immuable** :
- Rétention configurable (90 jours recommandés)
- Réplication factor ≥ 3 pour HA
- Schema Registry (Apache / Confluent) pour gouvernance des schémas

### 3.6 Consommateurs

| Famille | Rôle | Protocole |
|---|---|---|
| SOC | Ingestion dans SIEM, déclenchement SOAR | Kafka Consumer, Syslog, REST |
| Sync IAM | Provisioning/deprovisioning dans les systèmes cibles | Kafka Consumer, REST/SCIM |
| Audit | Archivage long terme, reporting conformité | Kafka Consumer, S3/ADLS |

---

## 4. Flux de données détaillé

```
DC-01 génère Event 4720 (création compte "john.doe")
  │
  ├─[WEF push]─► WEC Server
  │                   │
  │           ┌───────▼───────────────────────────────┐
  │           │  AGENT (Java)                         │
  │           │                                       │
  │           │  Adapter WEF                          │
  │           │  - Reçoit l'event brut XML            │
  │           │  - Filtre Event ID = 4720             │
  │           │  - Déduplique (EventRecordID + host)  │
  │           │             │                         │
  │           │  Normalizer                           │
  │           │  - Mappe vers RawIamEvent             │
  │           │  - Ajoute sourceId, detectedAt        │
  │           │             │                         │
  │           │  Kafka Publisher                      │
  │           │  - Publie avec retry + buffer disque  │
  │           └─────────────┼─────────────────────────┘
  │                         │
  │           ──────────────► topic: iam.raw.activedirectory
  │                         │
  │           Stream Processor (Kafka Streams / Flink)
  │           - Normalise en CloudEvents
  │           - Détecte: "hors heures ouvrées ? non"
  │           - Enrichit: manager LDAP, OU appartenances
  │                         │
  │           ──────────────► topic: iam.identity
  │                         │
  │                 ──────► Consommateur SOC → Splunk/Elastic
  │                 ──────► Consommateur IAM → Okta (SCIM)
  │                 ──────► Consommateur ITSM → ServiceNow ticket
  │
  │  (si compte créé en dehors des heures ou par un compte non référencé)
  │           ──────────────► topic: iam.alert
  │                         │
  │                 ──────► SOC → Alerte haute priorité
```

---

## 5. Décisions d'architecture (ADR)

### ADR-001 — WEF vs agent sur chaque DC
**Décision** : WEF + DirSync comme fallback  
**Raison** : Limite l'empreinte sur les DCs (agents évités), WEF est natif Windows et résistant aux pannes réseau. DirSync garantit aucune perte en cas de redémarrage DC.

### ADR-002 — Un Agent multi-adapters plutôt que des collecteurs séparés
**Décision** : Un binaire Java unique, stateless, avec des Adapters configurables par YAML  
**Raison** : Simplifie le déploiement et les mises à jour. Un seul processus à monitorer par instance. Le stateless permet de lancer N instances sans coordination.

### ADR-003 — Multi-instances pour la Haute Disponibilité
**Décision** : Plusieurs instances de l'Agent en parallèle, Kafka assure la déduplication par clé de partition  
**Raison** : Pas de SPOF sur la collecte. Si une instance tombe, les autres continuent. Pas besoin de coordination distribuée complexe entre les instances.

### ADR-004 — Kafka comme broker central
**Décision** : Kafka (ou Azure Event Hubs en mode Kafka-compatible)  
**Raison** : Rétention configurable, replay natif, écosystème de connecteurs riche.

### ADR-005 — CloudEvents comme format d'événement
**Décision** : Schéma CloudEvents 1.0 + Avro/JSON Schema Registry  
**Raison** : Standard vendor-neutral, interopérabilité, versioning des schémas.

### ADR-006 — Séparation topic raw vs topic normalisé
**Décision** : Topics internes `iam.raw.*` (non exposés) + topics publics normalisés  
**Raison** : Permet l'évolution du Normalizer sans impacter les consommateurs. Le raw permet le rejeu complet en cas de bug.
