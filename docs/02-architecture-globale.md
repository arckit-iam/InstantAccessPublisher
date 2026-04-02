# 02 — Architecture Globale

## 1. Principes directeurs

| Principe | Description |
|---|---|
| **Event-Driven** | AD est la source de vérité. Tout changement est un événement immuable. |
| **Découplage** | Aucun consommateur n'interroge AD directement. |
| **Single Source of Truth** | Le topic est le registre des faits, pas une représentation du présent. |
| **Idempotence** | Les consommateurs doivent tolérer un rejeu sans effet de bord. |
| **Defense in Depth** | La plateforme elle-même est sécurisée et auditée. |

---

## 2. Vue d'ensemble des couches

```
╔══════════════════════════════════════════════════════════════════════╗
║  COUCHE 1 — SOURCE                                                   ║
║                                                                      ║
║   ┌──────────┐   ┌──────────┐   ┌──────────┐                        ║
║   │   DC-01  │   │   DC-02  │   │   DC-N   │  Domain Controllers    ║
║   │ Win Sec  │   │ Win Sec  │   │ Win Sec  │  (Security Event Log)  ║
║   │  Event   │   │  Event   │   │  Event   │                        ║
║   └────┬─────┘   └────┬─────┘   └────┬─────┘                        ║
╚════════╪══════════════╪══════════════╪═══════════════════════════════╝
         │              │              │
╔════════╪══════════════╪══════════════╪═══════════════════════════════╗
║  COUCHE 2 — COLLECTE                │                                ║
║            ┌───────────────────────┐│                                ║
║            │   WEF Collector       ││  Windows Event Forwarding      ║
║            │   (WEC Server)        ◄┘  ou Winlogbeat sur chaque DC   ║
║            └──────────┬────────────┘                                 ║
║                       │                                              ║
║            ┌──────────▼────────────┐                                 ║
║            │   AD Watcher Service  │  Service .NET/Java/Go           ║
║            │   - Filtrage Event ID │  Écoute WEF ou Event Log        ║
║            │   - Déduplication     │  DirSync LDAP (backup)          ║
║            │   - Enrichissement    │                                  ║
║            └──────────┬────────────┘                                 ║
╚═══════════════════════╪══════════════════════════════════════════════╝
                        │
╔═══════════════════════╪══════════════════════════════════════════════╗
║  COUCHE 3 — TRAITEMENT│                                              ║
║            ┌──────────▼────────────┐                                 ║
║            │  Stream Processor     │  Kafka Streams / Flink          ║
║            │  - Normalisation      │  Azure Stream Analytics         ║
║            │  - Corrélation        │                                  ║
║            │  - Enrichissement     │                                  ║
║            │    (CMDB, LDAP)       │                                  ║
║            └──────────┬────────────┘                                 ║
╚═══════════════════════╪══════════════════════════════════════════════╝
                        │
╔═══════════════════════╪══════════════════════════════════════════════╗
║  COUCHE 4 — BROKER / TOPICS                                          ║
║            ┌──────────▼────────────┐                                 ║
║            │       KAFKA           │                                  ║
║            │  ┌──────────────────┐ │                                 ║
║            │  │ ad.raw.events    │ │  Raw events (internal)          ║
║            │  ├──────────────────┤ │                                 ║
║            │  │ ad.iam.identity  │ │  Événements IAM normalisés      ║
║            │  ├──────────────────┤ │                                 ║
║            │  │ ad.iam.group     │ │  Événements groupe              ║
║            │  ├──────────────────┤ │                                 ║
║            │  │ ad.iam.alert     │ │  Alertes SOC enrichies          ║
║            │  └──────────────────┘ │                                 ║
║            └──────────┬────────────┘                                 ║
╚═══════════════════════╪══════════════════════════════════════════════╝
                        │
         ┌──────────────┼──────────────────┐
         ▼              ▼                  ▼
╔════════════════╗ ╔══════════════╗ ╔══════════════════════╗
║  COUCHE 5a     ║ ║  COUCHE 5b   ║ ║  COUCHE 5c           ║
║  SOC           ║ ║  SYNC IAM    ║ ║  AUTRES              ║
║                ║ ║              ║ ║                      ║
║  • Splunk      ║ ║  • Okta      ║ ║  • ServiceNow        ║
║  • Elastic     ║ ║  • Azure AD  ║ ║  • Data Warehouse    ║
║  • QRadar      ║ ║  • SailPoint ║ ║  • Audit Trail       ║
║  • SOAR        ║ ║  • Système   ║ ║                      ║
║                ║ ║    RH maison ║ ║                      ║
╚════════════════╝ ╚══════════════╝ ╚══════════════════════╝
```

---

## 3. Description des composants

### 3.1 Domain Controllers (Source)

Les Domain Controllers Windows génèrent des **Security Event Logs** pour chaque opération IAM. Ces logs sont la **source canonique** des changements AD.

**Pré-requis** :
- Audit Policy activée sur les DCs : `Audit Account Management` en succès **et** échec
- Confirmation via `auditpol /get /category:"Account Management"`

### 3.2 Couche Collecte

Deux modes de collecte (complémentaires) :

**Mode primaire — Windows Event Forwarding (WEF)**
- Les DCs poussent leurs événements vers un **WEC Server** (Windows Event Collector) via WS-Management
- Un agent (Winlogbeat ou service custom) lit le canal WEF et pousse vers le pipeline
- Avantage : natif Windows, pas d'agent sur chaque DC

**Mode secondaire — DirSync LDAP**
- Polling LDAP avec le contrôle `1.2.840.113556.1.4.841` (DirSync)
- Détecte les changements d'objets LDAP même si les Event Logs sont partiellement manquants
- Sert de filet de sécurité et de réconciliation

### 3.3 AD Watcher Service

Microservice responsable de :
- Lire les événements bruts (WEF ou DirSync)
- Filtrer les Event IDs pertinents
- Dédupliquer (un même event peut venir de plusieurs DCs via la réplication)
- Enrichir avec des données LDAP de base (DN complet, UPN, département)
- Publier dans le topic `ad.raw.events`

### 3.4 Stream Processor

Responsable de :
- **Normaliser** les événements bruts en schéma CloudEvents
- **Détecter** les patterns métier (ajout à groupe sensible, compte créé en dehors des heures ouvrées, etc.)
- **Enrichir** depuis des sources externes (CMDB, référentiel RH)
- Router vers les topics thématiques (`ad.iam.identity`, `ad.iam.group`, `ad.iam.alert`)

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
  │           AD Watcher Service
  │           - Filtre Event ID = 4720
  │           - Déduplique (GUID + timestamp)
  │           - Enrichit : samAccountName, DN, département
  │           - Sérialise en JSON brut
  │                   │
  │           ──────► topic: ad.raw.events
  │                   │
  │           Stream Processor
  │           - Normalise en CloudEvents
  │           - Détecte: "hors heures ouvrées ? non"
  │           - Enrichit: manager LDAP, OU appartenances
  │                   │
  │           ──── ►  topic: ad.iam.identity
  │           │              │
  │           │      ──────► Consommateur SOC → Splunk/Elastic
  │           │      ──────► Consommateur IAM → Okta (SCIM)
  │           │      ──────► Consommateur ITSM → ServiceNow ticket
  │           │
  │           (si compte créé en dehors des heures ou par un compte non référencé)
  │           ──────► topic: ad.iam.alert
  │                   │
  │           ──────► SOC → Alerte haute priorité
```

---

## 5. Décisions d'architecture (ADR)

### ADR-001 — WEF vs Agent sur chaque DC
**Décision** : WEF + DirSync comme fallback  
**Raison** : Limite l'empreinte sur les DCs (agents évités), WEF est natif Windows et résistant aux pannes réseau (mode push avec reconnexion). DirSync garantit aucune perte en cas de redémarrage DC.

### ADR-002 — Kafka comme broker central
**Décision** : Kafka (ou Azure Event Hubs en mode Kafka-compatible)  
**Raison** : Rétention configurable, log compaction possible, écosystème de connecteurs riche, support du replay natif.

### ADR-003 — CloudEvents comme format d'événement
**Décision** : Schéma CloudEvents 1.0 + Avro/JSON Schema Registry  
**Raison** : Standard vendor-neutral, interopérabilité, versioning des schémas.

### ADR-004 — Séparation topic raw vs topic enrichi
**Décision** : Topic interne `ad.raw.events` (non exposé aux consommateurs) + topics publics enrichis  
**Raison** : Permettre l'évolution du pipeline de traitement sans impacter les consommateurs. Le raw permet le rejeu complet en cas de bug de traitement.
