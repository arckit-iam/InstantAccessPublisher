# 11 — Collecteurs de sources (Fluent Bit)

## 1. Principe

La collecte est standardisee avec **Fluent Bit** au lieu d'un agent Java custom.

Fluent Bit est deployee sur les serveurs de collecte :
- WEC pour les evenements Active Directory (`ForwardedEvents`),
- collecteurs syslog pour ISDS et equipements,
- eventuels points d'entree HTTP/fichiers selon sources.

```
[Source]              [Transport]         [Collecteur Fluent Bit]      [Topic raw]

AD (DCs)         ──► WEF             ──► input winlog             ──► iam.raw.activedirectory
ISDS / equip.    ──► Syslog UDP/TLS  ──► input syslog             ──► iam.raw.isds
Serveurs Linux   ──► Syslog / auditd ──► input syslog/tail        ──► iam.raw.syslog
Applications     ──► Webhook / CDC   ──► input http / tail bridge ──► iam.raw.{sourceId}

                                  Stream Processor
                                        │
                                        ▼
                    iam.identity / iam.entitlement / iam.assignment / iam.alert
                                        + iam.consolidated
```

---

## 2. Responsabilites

**Fluent Bit :**
- collecte des donnees brutes,
- tagging technique (`sourceId`, `sourceType`, `collectorHost`, `zone`),
- buffering local et publication fiable vers Kafka.

**Stream Processor :**
- normalisation metier IAM,
- enrichissement (LDAP, CMDB, UO AD),
- detection SOC,
- production du topic `iam.consolidated`.

**InstantAccessPublisher (Java) :**
- consommation de `iam.consolidated`,
- projection d'etat dans PostgreSQL (`identity_state`, `entitlement_state`, `assignment_state`).

---

## 3. Contrat de sortie de collecte

Tous les collecteurs publient des messages JSON bruts vers `iam.raw.{sourceId}` avec metadonnees minimales :

```json
{
  "sourceId": "ad://corp.example.com",
  "sourceType": "activedirectory",
  "collectorHost": "wec01.iap.local",
  "detectedAt": "2026-04-12T12:00:00Z",
  "nativePayload": "<Event ...>...</Event>"
}
```

---

## 4. Exemple de configuration Fluent Bit (WEC)

```ini
[SERVICE]
    Flush        1
    Log_Level    info
    storage.path C:\\fluent-bit\\storage

[INPUT]
    Name                 winlog
    Channels             ForwardedEvents
    DB                   C:\\fluent-bit\\state\\forwardedevents.db
    Read_Existing_Events false
    Tag                  wef.ad

[FILTER]
    Name    record_modifier
    Match   wef.ad
    Record  sourceId ad://corp.example.com
    Record  sourceType activedirectory

[OUTPUT]
    Name            kafka
    Match           wef.ad
    Brokers         kafka-broker-1:9092,kafka-broker-2:9092
    Topics          iam.raw.activedirectory
    Format          json
    rdkafka.request.required.acks all
```

---

## 5. Haute disponibilite et segmentation

- Plusieurs collecteurs Fluent Bit par zone (Tier0, Prod, Cloud).
- Un collecteur par region si necessaire.
- Pas de dependance a un binaire de collecte specifique metier.

---

## 6. Decision d'architecture

Le developpement d'un agent Java de collecte n'est plus retenu. Fluent Bit est la brique cible pour toutes les entrees de collecte. Le Java InstantAccessPublisher est reserve a la projection d'etat en temps reel depuis Kafka vers PostgreSQL.