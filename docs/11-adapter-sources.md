# 11 — Adaptateurs Sources (Source Adapters)

## 1. Principe

Chaque système source dispose d'un **Adapter** dédié. L'Adapter est le seul composant qui connaît le format natif du système source. Il traduit vers le modèle abstrait IAM (Identity / Entitlement / Assignment) défini dans `docs/00-modele-abstrait.md`.

```
Système Source ──► Adapter ──► iam.raw.{sourceSystem} ──► Normalizer ──► iam.identity
                                                                          iam.entitlement
                                                                          iam.assignment
```

L'interface de sortie est **identique** pour tous les adapters : un événement CloudEvents avec `type` en `com.example.iam.*`.

---

## 2. Interface contractuelle de l'Adapter

Tout adapter doit implémenter le contrat suivant :

```typescript
interface SourceAdapter {
  // Identifiant unique du système source (ex: "ad://corp.example.com")
  sourceId: string;

  // Type de source (pour le registre)
  sourceType: "activedirectory" | "ldap" | "business-application" | "entra-id" | "aws-iam" | "custom";

  // Démarrage de l'écoute / polling
  start(): Promise<void>;

  // Arrêt propre
  stop(): Promise<void>;

  // Callback de publication (injecté à l'initialisation)
  onEvent: (event: RawIamEvent) => Promise<void>;
}

interface RawIamEvent {
  sourceId: string;
  sourceType: string;
  nativeEventId: string;          // ID de l'événement dans le système source
  nativePayload: unknown;         // Payload brut non transformé
  detectedAt: string;             // ISO 8601
  deduplicationKey: string;       // Clé pour déduplication (idempotence)
}
```

---

## 3. AD Adapter (existant — remis en contexte générique)

Voir `docs/03-collecte-ad.md` pour les détails.

**Mapping** :

```
Event ID 4720 (user created)  → type: iam.identity.created
Event ID 4727/4731/4754       → type: iam.entitlement.created
Event ID 4728/4732/4756       → type: iam.assignment.added
Event ID 4729/4733/4757       → type: iam.assignment.removed
Event ID 4726                 → type: iam.identity.deleted
Event ID 4738                 → type: iam.identity.updated
```

---

## 4. Application Adapter (générique)

### 4.1 Modes de collecte pour les applications

Les applications tierces peuvent exposer leurs événements de plusieurs façons :

```
┌─────────────────────────────────────────────────────────────────────┐
│                  MODES DE COLLECTE APPLICATION                      │
│                                                                     │
│  Mode A — Webhook Push                                              │
│  Application ──────────────────────► App Adapter                   │
│  (POST HTTP sur endpoint dédié)       (valide + normalise)          │
│                                                                     │
│  Mode B — Polling REST API                                          │
│  App Adapter ──► GET /api/events?since=... ──► Application          │
│  (toutes les X secondes)                                            │
│                                                                     │
│  Mode C — CDC Base de données                                       │
│  App Adapter ──► Debezium/CDC ──► Tables métier de l'application    │
│  (Kafka Connect CDC, Debezium)                                      │
│                                                                     │
│  Mode D — File / Message Queue existante                            │
│  App Adapter ──► Lit queue existante (JMS, ActiveMQ, AMQP)         │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 Mode A — Webhook Push (recommandé pour les nouvelles apps)

L'application publie ses événements en POST sur l'endpoint de l'App Adapter.

**Endpoint App Adapter** :
```
POST /api/v1/events/{sourceId}
Authorization: Bearer {token}
Content-Type: application/json
```

**Payload attendu (format libre adapté par le mapping)** :

L'application A envoie ses événements dans son format natif, par exemple :

```json
{
  "eventType": "USER_PROFILE_ASSIGNED",
  "timestamp": "2026-04-02T15:01:00Z",
  "userId": "user-00234",
  "userEmail": "jane.smith@corp.example.com",
  "profileId": "profile-003",
  "profileName": "PROFIL_EXPORT_PAIE",
  "assignedBy": "user-admin-001"
}
```

**Configuration du mapping dans l'App Adapter** :

```yaml
# adapter-config/app-a.yaml
sourceId: "app://app-a.example.com"
sourceType: "business-application"
displayName: "Application A — Gestion RH"
webhook:
  secret: "${APP_A_WEBHOOK_SECRET}"   # HMAC-SHA256 pour valider l'origine

eventMappings:
  - nativeType: "USER_PROFILE_ASSIGNED"
    iamType: "iam.assignment.added"
    mapping:
      identity:
        externalId: "$.userId"
        type: "user"
        displayName: "$.userEmail"
        metadata:
          login: "$.userEmail"
      entitlement:
        externalId: "$.profileId"
        type: "app-profile"
        name: "$.profileName"
      assignment:
        grantedBy:
          externalId: "$.assignedBy"

  - nativeType: "USER_PROFILE_REVOKED"
    iamType: "iam.assignment.removed"
    mapping:
      identity:
        externalId: "$.userId"
        type: "user"
      entitlement:
        externalId: "$.profileId"
        type: "app-profile"
        name: "$.profileName"

  - nativeType: "PROFILE_CREATED"
    iamType: "iam.entitlement.created"
    mapping:
      entitlement:
        externalId: "$.profileId"
        type: "app-profile"
        name: "$.profileName"
        metadata:
          description: "$.description"
          ownerTeam: "$.ownerTeam"

  - nativeType: "USER_CREATED"
    iamType: "iam.identity.created"
    mapping:
      identity:
        externalId: "$.userId"
        type: "user"
        displayName: "$.userDisplayName"
        metadata:
          email: "$.email"
          tenant: "$.tenant"
```

**Avantages du mode webhook** :
- Latence quasi-nulle
- Pas de polling
- L'application contrôle ce qu'elle envoie

**Contrainte** : l'application doit être modifiable pour publier un webhook. Pour les applications legacy, utiliser le mode CDC ou API polling.

---

### 4.3 Mode B — Polling REST API

Pour les applications qui exposent une API `/events` ou `/audit-log` :

```yaml
# adapter-config/app-legacy.yaml
sourceId: "app://legacy-app.example.com"
sourceType: "business-application"
polling:
  endpoint: "https://legacy-app.example.com/api/audit-log"
  method: GET
  intervalSeconds: 30
  paginationParam: "cursor"
  auth:
    type: "api-key"
    header: "X-API-Key"
    secret: "${LEGACY_APP_API_KEY}"
  cursorStorage: "redis://cursor-store:6379/db/legacy-app"
```

---

### 4.4 Mode C — CDC Base de données (Debezium)

Pour les applications sans API d'événements, le **Change Data Capture** (CDC) capture les changements directement en base.

```
Application A
    │
    │ Tables: users, profiles, user_profile_assignments
    │
┌───▼───────────────────┐
│  Database (PostgreSQL,│
│  MySQL, Oracle…)      │
└───┬───────────────────┘
    │ Binlog / WAL
    ▼
┌───────────────────────┐
│  Debezium Connector   │
│  (Kafka Connect)      │
└───┬───────────────────┘
    │ table changes → raw Kafka topic
    ▼
┌───────────────────────┐
│  App CDC Normalizer   │
│  (Stream Processor)   │
│  Traduit les INSERT/  │
│  UPDATE/DELETE en     │
│  événements IAM       │
└───┬───────────────────┘
    │
    ▼ iam.assignment.added / removed / …
```

**Configuration Debezium example** :

```json
{
  "name": "app-a-postgres-cdc",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "app-a-db.internal",
    "database.dbname": "app_a",
    "database.user": "debezium-reader",
    "table.include.list": "public.users,public.profiles,public.user_profile_assignments",
    "topic.prefix": "cdc.app-a",
    "transforms": "route",
    "transforms.route.type": "org.apache.kafka.connect.transforms.ReplaceField$Value"
  }
}
```

**Mapping CDC → IAM** :

```
Table: user_profile_assignments
  INSERT → iam.assignment.added
  DELETE → iam.assignment.removed

Table: profiles
  INSERT → iam.entitlement.created
  UPDATE → iam.entitlement.updated
  DELETE (soft delete) → iam.entitlement.deleted

Table: users
  INSERT → iam.identity.created
  UPDATE (colonne active: true→false) → iam.identity.disabled
```

---

## 5. LDAP Adapter (générique)

```yaml
sourceId: "ldap://ldap.example.com"
sourceType: "ldap"
displayName: "OpenLDAP Interne"
ldap:
  url: "ldaps://ldap.example.com:636"
  bindDn: "cn=dirsync,ou=serviceaccounts,dc=example,dc=com"
  bindPassword: "${LDAP_BIND_PASSWORD}"
  baseDn: "dc=example,dc=com"
  pollIntervalSeconds: 15
  objectClasses:
    identity: ["inetOrgPerson", "posixAccount"]
    entitlement: ["groupOfNames", "posixGroup"]
  entitlementMemberAttribute: "member"
```

---

## 6. Azure AD / Entra ID Adapter (v2 roadmap)

Microsoft Graph API expose des **Change Notifications** (webhooks) et des **Delta Queries** pour capturer les changements en quasi-temps-réel.

```
Microsoft Graph API
  /users/delta                → iam.identity.created/updated/deleted
  /groups/delta               → iam.entitlement.created/updated/deleted
  /groups/{id}/members/delta  → iam.assignment.added/removed
```

**Mapping** :

| Microsoft Graph | IAM abstrait |
|---|---|
| User object | Identity (`type: user`) |
| Group object | Entitlement (`type: security-group` ou `distribution-group`) |
| group member delta | Assignment |
| servicePrincipal | Identity (`type: service`) |
| appRoleAssignment | Assignment |

---

## 7. Déploiement des adapters

Chaque adapter est un microservice indépendant, déployable en conteneur :

```yaml
# docker-compose.excerpt.yaml
services:
  ad-adapter:
    image: iam-platform/ad-adapter:2.0
    environment:
      SOURCE_ID: "ad://corp.example.com"
      WEC_HOST: wec-server.corp.internal
      KAFKA_BOOTSTRAP: kafka-1:9092,kafka-2:9092
      KAFKA_TOPIC_RAW: iam.raw.activedirectory
    volumes:
      - ./config/sensitive-groups.yaml:/app/config/sensitive-groups.yaml

  app-a-adapter:
    image: iam-platform/app-adapter:1.5
    environment:
      SOURCE_ID: "app://app-a.example.com"
      ADAPTER_CONFIG: /app/config/app-a.yaml
      KAFKA_BOOTSTRAP: kafka-1:9092,kafka-2:9092
      KAFKA_TOPIC_RAW: iam.raw.app-a
    ports:
      - "8080:8080"   # Webhook endpoint
    volumes:
      - ./config/app-a.yaml:/app/config/app-a.yaml

  ldap-adapter:
    image: iam-platform/ldap-adapter:1.2
    environment:
      SOURCE_ID: "ldap://ldap.example.com"
      ADAPTER_CONFIG: /app/config/ldap.yaml
      KAFKA_BOOTSTRAP: kafka-1:9092,kafka-2:9092
      KAFKA_TOPIC_RAW: iam.raw.ldap
```

---

## 8. Ajout d'une nouvelle source — Procédure

```
1. Enregistrer la source dans le Source Registry
   POST /api/v1/sources
   { sourceId, sourceType, displayName, entityMapping, sensitiveEntitlements }

2. Choisir le mode de collecte (Webhook / Polling / CDC / Queue)

3. Créer le fichier de configuration du mapping
   adapter-config/{sourceId}.yaml

4. Déployer l'adapter correspondant (image existante ou custom)

5. Créer le topic raw dédié : iam.raw.{sourceId-sanitized}

6. Configurer les règles de détection SOC spécifiques à la source
   (ex: quels profils applicatifs sont "sensibles" ?)

7. Valider via un événement de test end-to-end
```
