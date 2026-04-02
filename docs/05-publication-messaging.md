# 05 — Publication : Bus de Messages et Topics

> Ce document utilise la nomenclature du modèle abstrait IAM défini dans `docs/00-modele-abstrait.md`.
> Les notions de **groupe AD**, **profil applicatif**, **groupOfNames LDAP** sont toutes des `Entitlement`.
> Les topics `iam.*` reçoivent les événements de **toutes les sources** (AD, applications, LDAP…).

## 1. Topologie des topics Kafka

```
                     ┌──────────────────────────────────────────┐
                     │              KAFKA CLUSTER               │
                     │                                          │
  AD Adapter ──────► │  iam.raw.activedirectory (interne)      │
  App Adapter ────► │  iam.raw.app-a           (interne)      │
  LDAP Adapter ──► │  iam.raw.ldap            (interne)      │
                     │  └─ 1 topic raw par source, RF:3, 7j     │
                     │                                          │
  Stream        ┌──► │  iam.identity          (public)        │
  Processor ────┤    │  Partitions: 6,  RF: 3, Retention: 90j  │
  (normalise    ├──► │  iam.entitlement       (public)        │
   toutes les   │    │  Partitions: 6,  RF: 3, Retention: 90j  │
   sources)     ├──► │  iam.assignment        (public)        │
                │    │  Partitions: 6,  RF: 3, Retention: 90j  │
                ├──► │  iam.alert             (public)        │
                │    │  Partitions: 3,  RF: 3, Retention: 90j  │
                └──► │  iam.dlq               (ops)           │
                     │  Partitions: 3,  RF: 3, Retention: 30j  │
                     │                                          │
                     │  iam.audit             (archivage)      │
                     │  Partitions: 6,  RF: 3, Retention: 365j │
                     └──────────────────────────────────────────┘
```

---

## 2. Description des topics

### `iam.raw.{sourceSystem}` — Interne (1 topic par source)

| Propriété | Valeur |
|---|---|
| Visibilité | Interne (pipeline uniquement, ACL restreint) |
| Format | JSON brut propre à chaque source |
| Exemples | `iam.raw.activedirectory`, `iam.raw.app-a`, `iam.raw.ldap` |
| Partitionnement | Par `sourceId` |
| Rétention | 7 jours (permet le replay du pipeline) |
| Compaction | Non |

**Pourquoi un topic par source ?** Permet de rejouer uniquement une source sans impacter les autres, et d'appliquer des schemas différents par source.

---

### `iam.identity` — Événements Identity (toutes sources)

| Propriété | Valeur |
|---|---|
| Visibilité | Public (consommateurs IAM, SOC, Audit) |
| Format | CloudEvents 1.0 + JSON Schema v3 |
| Partitionnement | Par `data.identity.id` (ordre garanti par identité) |
| Rétention | 90 jours |
| Compaction | Non (log des faits, pas état courant) |

**Types** : `iam.identity.created` / `iam.identity.updated` / `iam.identity.enabled` / `iam.identity.disabled` / `iam.identity.deleted`

**Sources** : AD (compte user), Application A (compte applicatif), LDAP (inetOrgPerson)…  
Le champ `source` du CloudEvent identifie l'origine.

---

### `iam.entitlement` — Événements Entitlement (groupe, profil, rôle)

| Propriété | Valeur |
|---|---|
| Visibilité | Public |
| Format | CloudEvents 1.0 + JSON Schema v3 |
| Partitionnement | Par `data.entitlement.id` |
| Rétention | 90 jours |
| Compaction | Non |

**Types** : `iam.entitlement.created` / `iam.entitlement.updated` / `iam.entitlement.deleted`

**Sources** : AD (security group), Application A (profil applicatif), LDAP (groupOfNames)…  
Le champ `data.entitlement.type` précise la nature : `security-group`, `app-profile`, `role`, `permission-set`…

---

### `iam.assignment` — Événements d'affectation Identity → Entitlement

| Propriété | Valeur |
|---|---|
| Visibilité | Public |
| Format | CloudEvents 1.0 + JSON Schema v3 |
| Partitionnement | Par `data.identity.id` (ordre des affectations par identité) |
| Rétention | 90 jours |
| Compaction | Non |

**Types** : `iam.assignment.added` / `iam.assignment.removed`

**Sources** : AD (memberOf), Application A (User → Profil), LDAP (member), Entra ID (group membership)…

> **Note** : c'est ce topic qui était ancèrement divisé entre `ad.iam.group` (lifecycle) et la partie membership. Il est maintenant clairement séparé. Les consommateurs de synchronisation écouteront principalement `iam.assignment`.

---

### `iam.alert` — Alertes SOC

| Propriété | Valeur |
|---|---|
| Visibilité | SOC uniquement (ACL strict) |
| Format | CloudEvents 1.0 avec extension `alert` |
| Partitionnement | Par sévérité (`CRITICAL`=0, `HIGH`=1, `MEDIUM`=2) |
| Rétention | 90 jours |
| Compaction | Non |

Les règles SOC s'appliquent indépendamment de la source. Un ajout à `PROFIL_ADMIN_*` dans l'Application A déclenche exactement les mêmes règles qu'un ajout à `Domain Admins` dans AD.

---

### `iam.audit` — Journal d'audit long terme

| Propriété | Valeur |
|---|---|
| Visibilité | Audit / Conformité |
| Format | JSON signé (HMAC-SHA256) |
| Partitionnement | Par mois (facilite l'archivage S3/ADLS) |
| Rétention | 365 jours (puis archivage cold storage) |
| Compaction | Non |

---

## 3. Gestion des schémas (Schema Registry)

Tous les topics publics utilisent un **Schema Registry** (Confluent ou Apicurio) pour garantir la compatibilité évolutive.

### Stratégie de compatibilité

```
BACKWARD_TRANSITIVE : 
  - Les consommateurs sur la version N peuvent lire les messages de la version N-1
  - Les producteurs doivent soumettre les nouvelles versions au registry
  - Pas de suppression de champ obligatoire
  - Pas de changement de type de champ
```

### Schéma JSON générique : `iam.assignment.added` (v3)

Ce schéma est **source-agnostique** : il fonctionne pour un memberOf AD, une affectation de profil applicatif, un role binding Kubernetes, etc.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schema-registry.internal/iam-events/assignment-added/v3",
  "type": "object",
  "required": ["specversion", "id", "source", "type", "time", "data"],
  "properties": {
    "specversion": { "const": "1.0" },
    "id": { "type": "string", "format": "uuid" },
    "source": { "type": "string", "format": "uri",
                "description": "URI du système source. Ex: ad://corp.example.com, app://app-a.example.com" },
    "type": { "const": "com.example.iam.assignment.added" },
    "time": { "type": "string", "format": "date-time" },
    "iamsourcetype": {
      "type": "string",
      "enum": ["activedirectory", "ldap", "business-application", "entra-id", "aws-iam", "custom"],
      "description": "Type du système source pour le routage et les règles spécifiques"
    },
    "data": {
      "type": "object",
      "required": ["assignment", "identity", "entitlement"],
      "properties": {
        "assignment": {
          "type": "object",
          "required": ["id", "grantedAt"],
          "properties": {
            "id": { "type": "string" },
            "grantedAt": { "type": "string", "format": "date-time" },
            "expiresAt": { "type": "string", "format": "date-time" },
            "grantedBy": {
              "type": "object",
              "properties": {
                "externalId": { "type": "string" },
                "displayName": { "type": "string" },
                "sourceSystem": { "type": "string" }
              }
            }
          }
        },
        "identity": {
          "type": "object",
          "required": ["externalId", "sourceSystem", "type"],
          "properties": {
            "id": { "type": "string", "format": "uuid",
                    "description": "ID stable plateforme (optionnel si non encore résolu)" },
            "externalId": { "type": "string",
                            "description": "ID dans le système source (SID, UUID appli, DN LDAP…)" },
            "sourceSystem": { "type": "string" },
            "type": { "enum": ["user", "service", "machine", "robot", "guest", "unknown"] },
            "displayName": { "type": "string" },
            "metadata": { "type": "object",
                          "description": "Champs libres spécifiques à la source (samAccountName, upn, login, email…)" }
          }
        },
        "entitlement": {
          "type": "object",
          "required": ["externalId", "sourceSystem", "type", "name"],
          "properties": {
            "id": { "type": "string", "format": "uuid" },
            "externalId": { "type": "string",
                            "description": "ID dans le système source (SID groupe, ID profil, ARN rôle…)" },
            "sourceSystem": { "type": "string" },
            "type": {
              "enum": ["security-group", "distribution-group", "app-profile",
                       "role", "permission-set", "other"],
              "description": "Nature de l'entitlement : ce champ permet au consommateur de comprendre la sémantique sans connaître la source"
            },
            "name": { "type": "string" },
            "sensitivityLevel": { "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"] },
            "metadata": { "type": "object" }
          }
        },
        "context": {
          "type": "object",
          "properties": {
            "isOutsideBusinessHours": { "type": "boolean" },
            "isPrivilegedEntitlement": { "type": "boolean" },
            "sourceNativeEventId": { "type": "string" }
          }
        }
      }
    }
  }
}
```

---

## 4. Partitionnement et ordering

### Garantie d'ordre

```
Tous les événements concernant la même Identity ou le même
Entitlement sont écrits dans la même partition → ordre garanti.

Partition key:
  - iam.identity    : data.identity.id  (UUID plateforme stable)
  - iam.entitlement : data.entitlement.id
  - iam.assignment  : data.identity.id  (ordre des affectations par identité)
  - iam.alert       : severity (CRITICAL=0, HIGH=1, MEDIUM=2)
```

> **Pourquoi `identity.id` plutôt que `externalId` ?** Un même utilisateur peut avoir un compte AD ET un compte dans l'Application A. Les partitionner par `identity.id` (UUID plateforme = émis après résolution/corrélation) permet de regrouper tous les événements d'une même personne physique dans la même partition. Si la corrélation cross-source n'est pas encore implémentée, on utilise `externalId` comme clé.

### Calcul du nombre de partitions

Pour `iam.assignment` avec 10 000 événements/heure toutes sources = ~2.8 evt/sec :
→ 6 partitions suffisantes, permettent 6 consommateurs en parallèle maximum.
Avec croissance multi-sources (5 systèmes), scaler à 12 partitions.

---

## 5. Configuration Kafka recommandée

### Producer (AD Watcher → ad.raw.events)

```properties
# Garantie de non-perte
acks=all
retries=2147483647
enable.idempotence=true
max.in.flight.requests.per.connection=5

# Performance
batch.size=16384
linger.ms=5
compression.type=lz4

# Sécurité
security.protocol=SSL
ssl.truststore.location=/etc/kafka/ssl/truststore.jks
ssl.keystore.location=/etc/kafka/ssl/keystore.jks
```

### Consumer (SIEM, IAM sync)

```properties
# Exactement une fois par groupe de consommateurs
group.id=soc-siem-consumer
auto.offset.reset=earliest
enable.auto.commit=false
isolation.level=read_committed

# Traitement idempotent recommandé côté consommateur
max.poll.records=100
max.poll.interval.ms=300000
```

### Topic (création via terraform / CLI)

```bash
kafka-topics.sh --create \
  --topic ad.iam.identity \
  --partitions 6 \
  --replication-factor 3 \
  --config retention.ms=7776000000 \
  --config min.insync.replicas=2 \
  --config message.timestamp.type=CreateTime \
  --config compression.type=lz4
```

---

## 6. Alternatives au broker Kafka

| Broker | Avantages | Inconvénients | Cas d'usage |
|---|---|---|---|
| **Apache Kafka** | Rétention native, replay, très haute perf, Kafka Connect | Opérationnel complexe, overhead infra | On-premise, large volume |
| **Azure Event Hubs** | Compatible Kafka API, managé, intégration Azure AD native | Vendor lock-in Azure, coût | Full Azure / Hybrid |
| **AWS MSK** | Kafka managé AWS | Vendor lock-in AWS | Full AWS |
| **Azure Service Bus** | Simple, sessions (ordering), FIFO garanti | Pas de replay long terme natif | Azure, faible volume |
| **RabbitMQ** | Simple à opérer, bonne UX | Pas de rétention longue, pas de replay | Petit périmètre |
| **NATS JetStream** | Ultra léger, performant | Écosystème plus restreint | Edge / IoT |

**Recommandation** : Kafka (on-premise) ou Azure Event Hubs (cloud) selon l'infrastructure existante. L'API Kafka-compatible d'Event Hubs permet de migrer sans changer le code.

---

## 7. Monitoring des topics

Métriques à surveiller via **Kafka JMX + Prometheus + Grafana** :

| Métrique | Seuil alerte | Signification |
|---|---|---|
| `consumer_lag` par groupe | > 1000 | Consommateur en retard |
| `ad.dlq.events` offset rate | > 0 | Erreurs de traitement |
| Producer `error-rate` | > 0 | Perte potentielle d'événements |
| `ad.raw.events` throughput | Baisse soudaine | Problème collecte DC |
| `under-replicated-partitions` | > 0 | Problème cluster Kafka |
