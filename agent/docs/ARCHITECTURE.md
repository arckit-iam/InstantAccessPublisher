# Architecture technique — iap-agent

## Vue d'ensemble

L'agent est une application **Spring Boot 3 / Java 21** qui collecte des événements IAM
depuis des sources natives (WEF, DirSync, Syslog, Webhook) et les publie sur Kafka
au format CloudEvents 1.0 JSON.

---

## Architecture hexagonale

```
┌─────────────────────────────────────────────────────────────────┐
│                          iap-agent                              │
│                                                                 │
│  Adapters IN (infrastructure.in)                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │   WEF    │ │ DirSync  │ │  Syslog  │ │ Webhook  │           │
│  │(wevtapi) │ │  (LDAP)  │ │  (TCP)   │ │  (HTTP)  │           │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘           │
│       │             │             │             │                │
│       └─────────────┴─────────────┴─────────────┘               │
│                             │                                    │
│                    EventCollectionUseCase (port IN)              │
│                             │                                    │
│                    ┌────────▼────────┐                           │
│                    │ AgentOrchestrator│  (application)           │
│                    └────────┬────────┘                           │
│                             │                                    │
│                    EventPublisher (port OUT)                     │
│                             │                                    │
│                    ┌────────▼────────┐                           │
│                    │KafkaEventPublisher│ (infrastructure.out)   │
│                    └─────────────────┘                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Packages

```
com.instantaccessprovider.agent
├── AgentApplication.java              — Bootstrap Spring Boot + démarrage des adapters
│
├── application/
│   └── AgentOrchestrator.java         — Implémente EventCollectionUseCase → délègue à EventPublisher
│
├── config/
│   └── AgentProperties.java           — @ConfigurationProperties iap.agent.*
│
├── domain/
│   ├── model/
│   │   ├── RawIamEvent.java            — Événement IAM immuable (record)
│   │   ├── ResourceContext.java        — Ressource cible (type + ref)
│   │   └── SourceType.java             — Enum : ACTIVE_DIRECTORY, SYSLOG…
│   └── port/
│       ├── in/
│       │   ├── EventCollectionUseCase.java  — Port primaire : handle(RawIamEvent)
│       │   └── InputAdapter.java            — Contrat des adapters entrants
│       └── out/
│           └── EventPublisher.java          — Port secondaire : publish(RawIamEvent)
│
└── infrastructure/
    ├── in/
    │   ├── wef/
    │   │   ├── WefAdapter.java          — Démarre/arrête la souscription WEF
    │   │   ├── WefEventProcessor.java   — Parse, filtre, construit RawIamEvent
    │   │   ├── WinEvtSubscription.java  — JNA EvtSubscribe (callback natif Windows)
    │   │   ├── WinEvtApi.java           — Interface JNA wevtapi.dll
    │   │   ├── WefXmlParser.java        — Parse le XML Windows Event Log
    │   │   ├── WefRawEvent.java         — DTO intermédiaire (record)
    │   │   └── WefParseException.java
    │   ├── dirsync/
    │   │   └── DirsyncAdapter.java      — Polling LDAP (DirSync control)
    │   ├── syslog/
    │   │   └── SyslogAdapter.java       — Écoute TCP/UDP syslog
    │   └── webhook/
    │       └── WebhookAdapter.java      — Écoute HTTP POST
    ├── out/
    │   └── kafka/
    │       ├── KafkaConfig.java          — ProducerFactory + KafkaTemplate typé
    │       ├── KafkaEventPublisher.java  — Implémente EventPublisher → Kafka send
    │       ├── KafkaTopicResolver.java   — sourceId → nom de topic
    │       └── schema/
    │           ├── RawIamEventMessage.java  — Enveloppe CloudEvents 1.0
    │           └── RawIamEventData.java     — Champ data de l'enveloppe
    └── resource/
        └── ResourceContextResolver.java  — Résout ResourceContext via règles regex
```

---

## Flux WEF (adapter principal)

```
wevtapi.dll (Windows)
  └── EvtSubscribe(channel=ForwardedEvents, xpath=*[System[EventID=4720 or ...]])
        └── callback (thread pool Windows)
              └── WinEvtSubscription.renderXml()    — EvtRender → XML brut
                    └── WefEventProcessor.process()
                          ├── WefXmlParser.parse()  — extrait eventId, recordId, computer, channel
                          ├── filtre sur allowedEventIds
                          ├── ResourceContextResolver.resolve()  — règles regex optionnelles
                          ├── RawIamEvent.of(sourceId, ACTIVE_DIRECTORY, nativeEventId,
                          │                  nativeEventType, rawXml, now, resource)
                          └── EventCollectionUseCase.handle(event)
                                └── AgentOrchestrator
                                      └── KafkaEventPublisher.publish(event)
                                            └── KafkaTemplate.send(topic, deduplicationKey, message)
```

---

## Modèle de données

### `RawIamEvent` (domaine)

| Champ | Type | Description |
|---|---|---|
| `sourceId` | String | URI de la source (`ad://corp.client.com`) |
| `sourceType` | SourceType | `ACTIVE_DIRECTORY`, `SYSLOG`… |
| `nativeEventId` | String | Identifiant unique d'occurrence (`recordId@computer`) |
| `nativeEventType` | String? | Type d'événement source (`4720`, `4726`…) |
| `nativePayload` | String | Payload brut (XML WEF, JSON, CEF…) |
| `detectedAt` | Instant | Horodatage de détection par l'agent |
| `deduplicationKey` | String | SHA-256(sourceId \| nativeEventId \| timestamp/s) |
| `resource` | ResourceContext? | Ressource cible optionnelle (type + ref) |

### Message Kafka (`RawIamEventMessage` — CloudEvents 1.0)

```json
{
  "specversion":     "1.0",
  "id":              "<uuid>",
  "source":          "ad://corp.client.com",
  "type":            "com.instantaccessprovider.iam.raw.event",
  "time":            "2026-04-07T10:23:00Z",
  "datacontenttype": "application/json",
  "iamsourcetype":   "active_directory",
  "data": {
    "sourceId":         "ad://corp.client.com",
    "sourceType":       "active_directory",
    "nativeEventId":    "12345@DC01.corp.client.com",
    "nativeEventType":  "4720",
    "nativePayload":    "<Event>...</Event>",
    "detectedAt":       "2026-04-07T10:23:00Z",
    "deduplicationKey": "a3f2b1c4...",
    "resourceType":     "application",
    "resourceRef":      "SX123456"
  }
}
```

### Convention de nommage des topics

```
iam.raw.<sourceId-sanitisé>
```

Sanitisation : toLowerCase, `://` → `--`, `.` → `-`, caractères non alphanum → `-`

| sourceId | Topic |
|---|---|
| `ad://corp.client.com` | `iam.raw.ad--corp-client-com` |
| `ad://iap.local` | `iam.raw.ad--iap-local` |

---

## Configuration (`AgentProperties`)

Lire `application.yml` et `.env.example` pour les valeurs par défaut et la stratégie
de surcharge (yml < yml-profil < variables d'environnement).

### Stratégie de configuration multi-client

```
application.yml          ← socle produit (dans le repo, commité)
application-{profil}.yml ← règles métier client (repo config séparé)
Variables d'env          ← secrets et URLs (Vault, K8s Secret, .env)
```

Le fichier de règles client est désigné par `IAP_CONFIG_FILE` — il peut être un
classpath resource (dev) ou un fichier externe (prod, ConfigMap K8s, volume Docker).

---

## Déduplication

La clé Kafka est le `deduplicationKey` : `SHA-256(sourceId | nativeEventId | timestamp/s)`.

- Deux events identiques dans la même seconde (réplication AD multi-DC) → même clé → dédupliqués par les consommateurs idempotents.
- Le **producteur** est configuré `enable.idempotence=true` + `acks=all` → pas de doublon côté broker non plus.

---

## Sécurité

- Le `nativePayload` XML peut contenir des données AD sensibles (noms de comptes, DNs).  
  **Ne jamais loguer `nativePayload` en clair au-delà du niveau TRACE.**
- Le compte exécutant l'agent n'a besoin que du droit **Event Log Readers** — principe de moindre privilège.
- Les secrets (mots de passe LDAP, keystores Kafka) passent exclusivement par variables d'environnement, jamais dans `application.yml`.

---

## Tests

```
66 tests — 0 failures

agent/src/test/java/
├── application/
│   └── AgentOrchestratorTest.java        — useCase → publisher (mock)
├── domain/model/
│   └── RawIamEventTest.java              — deduplicationKey, factory methods
└── infrastructure/
    ├── in/wef/
    │   ├── WefAdapterTest.java            — buildXpathQuery
    │   ├── WefEventProcessorTest.java     — filtrage, construction RawIamEvent, ResourceContext
    │   └── WefXmlParserTest.java          — parsing XML Windows Event Log
    ├── out/kafka/
    │   ├── KafkaEventPublisherTest.java   — send, async failure, CloudEvents mapping
    │   └── KafkaTopicResolverTest.java    — sanitisation sourceId → topic
    └── resource/
        └── ResourceContextResolverTest.java — règles regex, groupes de capture, priorité
```

Lancer les tests :
```bash
# Depuis agent/
mvn test

# Test unique
mvn test -Dtest=WefEventProcessorTest
```

---

## Développement local

### Prérequis

- Java 21 ([Microsoft Build of OpenJDK](https://aka.ms/download-jdk))
- Docker Desktop
- Maven (ou utiliser `~/.m2/wrapper/dists/...`)

### Démarrage

```powershell
# 1. Kafka local
cd agent
docker compose -f docker-compose.dev.yml up kafka -d

# 2. Configurer .env
Copy-Item .env.example ..\.env
# Éditer ..\.env :
#   IAP_AGENT_ADAPTERS_WEF_ENABLED=true
#   IAP_WEF_SOURCE_ID=ad://iap.local
#   IAP_WEF_REMOTE_HOST=WEC01.iap.local    (ou vide si agent sur WEC01)
#   SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# 3. Build
mvn package -DskipTests

# 4. Lancer
Get-Content ..\.env | Where-Object { $_ -notmatch '^#' -and $_ -ne '' } |
  ForEach-Object { $k,$v = $_ -split '=',2; [System.Environment]::SetEnvironmentVariable($k,$v) }
java -jar target\iap-agent-0.1.0-SNAPSHOT.jar

# 5. Kafka UI (optionnel)
docker run -d --name kafka-ui -p 8080:8080 \
  -e KAFKA_CLUSTERS_0_NAME=local \
  -e KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=host.docker.internal:9092 \
  provectuslabs/kafka-ui:latest
# → http://localhost:8080
```

### Contrainte Windows

`WefAdapter` utilise `wevtapi.dll` — il ne peut s'exécuter que sur Windows.
Sur les autres OS, désactiver l'adapter (`IAP_AGENT_ADAPTERS_WEF_ENABLED=false`).
Les tests unitaires sont OS-agnostiques (la couche JNA est isolée dans `WinEvtSubscription`).
