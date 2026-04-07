# Guide d'intégration — InstantAccessProvider Agent

Ce guide s'adresse à l'intégrateur ou à l'administrateur système qui installe
l'agent IAP dans l'environnement du client.

---

## Vue d'ensemble

```
Active Directory (DC)
  └── Windows Event Forwarding
        └── WEC (Windows Event Collector)
              └── iap-agent  ──→  Kafka  ──→  Consommateurs (SOC, IAM, SIEM…)
```

L'agent tourne sur le **serveur WEC**, lit le canal `ForwardedEvents` en local
et publie chaque événement IAM en JSON (format CloudEvents 1.0) sur un topic Kafka.

---

## Prérequis

### Infrastructure

| Composant | Version minimale | Notes |
|---|---|---|
| Windows Server | 2019 ou supérieur | Serveur WEC et hôte de l'agent |
| Java | 21 (JRE) | [Microsoft Build of OpenJDK](https://aka.ms/download-jdk) |
| Kafka | 3.x | Accessible depuis le serveur WEC (port 9092 ou 9093 TLS) |

### Rôles Windows requis sur le serveur WEC

- Le compte qui exécute l'agent doit être membre du groupe local **Event Log Readers**
- La souscription WEF doit être active et le canal `ForwardedEvents` doit contenir des événements

### Connectivité réseau

| Source | Destination | Port | Protocole |
|---|---|---|---|
| Serveur WEC (agent) | Broker Kafka | 9092 (ou 9093) | TCP |
| DCs | Serveur WEC | 5985 (WinRM) | TCP |

---

## Installation

### 1. Copier le jar sur le serveur WEC

```powershell
# Depuis la machine de déploiement
New-Item -ItemType Directory -Path C:\iap -Force
Copy-Item iap-agent-<version>.jar C:\iap\iap-agent.jar
```

### 2. Créer le fichier de configuration

Créer `C:\iap\.env` avec le contenu suivant, adapté à l'environnement :

```env
# Identifiant unique de cet agent (libre, utilisé pour les logs)
IAP_INSTANCE_ID=agent-wec01-prod

# Kafka — adapter l'adresse du broker
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka.interne.client.com:9092

# Adapter WEF — lecture locale du canal ForwardedEvents sur ce serveur
IAP_AGENT_ADAPTERS_WEF_ENABLED=true
IAP_WEF_REMOTE_HOST=
IAP_WEF_SOURCE_ID=ad://corp.client.com

# Fichier de règles de résolution de ressource (optionnel)
# Pointer vers un fichier YAML fourni par l'éditeur pour le client
IAP_CONFIG_FILE=file:C:\iap\rules.yml

# Niveau de log (INFO en prod, DEBUG pour le diagnostic)
IAP_LOG_LEVEL=INFO
```

> **Sécurité** : le fichier `.env` contient des informations sensibles.
> Restreindre les droits en lecture au seul compte de service :
> ```powershell
> icacls C:\iap\.env /inheritance:r /grant "DOMAIN\svc-iap:(R)"
> ```

### 3. Lancer l'agent (test manuel)

```powershell
# Charger les variables d'environnement et démarrer
Get-Content C:\iap\.env |
  Where-Object { $_ -notmatch '^#' -and $_ -ne '' } |
  ForEach-Object { $k,$v = $_ -split '=',2; [System.Environment]::SetEnvironmentVariable($k,$v) }

java -jar C:\iap\iap-agent.jar
```

Vérifier dans les logs que les lignes suivantes apparaissent :

```
INFO  c.i.a.AgentApplication    : Started AgentApplication in X seconds
INFO  c.i.a.i.in.wef.WefAdapter : WEF adapter démarré — canal=ForwardedEvents eventIds=[...]
```

### 4. Installer comme service Windows

```powershell
# Créer le service avec NSSM (https://nssm.cc/) — recommandé
nssm install IapAgent "C:\Program Files\Microsoft\jdk-21...\bin\java.exe"
nssm set IapAgent AppParameters "-jar C:\iap\iap-agent.jar"
nssm set IapAgent AppDirectory C:\iap
nssm set IapAgent AppEnvironmentExtra `
  "IAP_INSTANCE_ID=agent-wec01-prod" `
  "SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka.interne.client.com:9092" `
  "IAP_AGENT_ADAPTERS_WEF_ENABLED=true" `
  "IAP_WEF_SOURCE_ID=ad://corp.client.com" `
  "IAP_CONFIG_FILE=file:C:\iap\rules.yml" `
  "IAP_LOG_LEVEL=INFO"
nssm set IapAgent AppStdout C:\iap\logs\iap-agent.log
nssm set IapAgent AppStderr C:\iap\logs\iap-agent-error.log
nssm set IapAgent ObjectName "DOMAIN\svc-iap" <mot_de_passe>
Start-Service IapAgent
```

---

## Configuration avancée

### Règles de résolution de ressource (`rules.yml`)

Ce fichier YAML permet de mapper les événements AD vers des types de ressources métier.
Il est fourni par l'éditeur, adapté à la topologie Active Directory du client (OU, naming).

Exemple :

```yaml
iap:
  agent:
    resource-mappings:
      # Tout utilisateur dans OU=Applications → ressource de type "application"
      - match-field: payload
        pattern: 'OU=([A-Za-z0-9_-]+),OU=Applications,'
        resource-type: application
        resource-ref: "$1"

      # Groupe de sécurité dans OU=ServiceAccounts
      - match-field: payload
        pattern: 'OU=ServiceAccounts,OU=([^,]+),'
        resource-type: service_account
        resource-ref: "$1"
```

### Kafka avec TLS

```env
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka.interne.client.com:9093
SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL=SSL
SPRING_KAFKA_SSL_TRUST_STORE_LOCATION=file:C:\iap\kafka.truststore.jks
SPRING_KAFKA_SSL_TRUST_STORE_PASSWORD=<mot_de_passe>
SPRING_KAFKA_SSL_KEY_STORE_LOCATION=file:C:\iap\kafka.keystore.jks
SPRING_KAFKA_SSL_KEY_STORE_PASSWORD=<mot_de_passe>
```

---

## Vérification post-installation

### 1. Vérifier les événements dans ForwardedEvents

```powershell
Get-WinEvent -LogName ForwardedEvents -MaxEvents 10 |
  Where-Object { $_.Id -in @(4720,4722,4725,4726,4727,4728,4729,4730,
                              4731,4732,4733,4734,4738,4754,4756,4757,4758,4781) } |
  Select-Object TimeCreated, Id, MachineName |
  Format-Table -AutoSize
```

### 2. Vérifier les topics Kafka

Le topic créé automatiquement suit la convention :

```
iam.raw.<sourceId-sanitisé>
```

Exemple avec `IAP_WEF_SOURCE_ID=ad://corp.client.com` → topic `iam.raw.ad--corp-client-com`

```bash
# Depuis une machine avec les outils Kafka
kafka-console-consumer.sh \
  --bootstrap-server kafka.interne.client.com:9092 \
  --topic iam.raw.ad--corp-client-com \
  --from-beginning \
  --max-messages 5
```

### 3. Format d'un message publié

```json
{
  "specversion": "1.0",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "source": "ad://corp.client.com",
  "type": "com.instantaccessprovider.iam.raw.event",
  "time": "2026-04-07T10:23:00Z",
  "datacontenttype": "application/json",
  "iamsourcetype": "active_directory",
  "data": {
    "sourceId": "ad://corp.client.com",
    "sourceType": "active_directory",
    "nativeEventId": "12345@DC01.corp.client.com",
    "nativeEventType": "4720",
    "nativePayload": "<Event>...</Event>",
    "detectedAt": "2026-04-07T10:23:00Z",
    "deduplicationKey": "a3f2b1...",
    "resourceType": "application",
    "resourceRef": "SX123456"
  }
}
```

---

## Dépannage

| Symptôme | Cause probable | Action |
|---|---|---|
| `GetLastError=1722` au démarrage | RPC bloqué — agent pas sur le WEC | Déplacer l'agent sur WEC01, laisser `IAP_WEF_REMOTE_HOST` vide |
| `GetLastError=5` (Access Denied) | Compte non membre d'Event Log Readers | `Add-LocalGroupMember -Group "Event Log Readers" -Member "DOMAIN\svc-iap"` |
| Aucun message dans Kafka | WEF adapter démarre mais aucun event AD depuis le démarrage | Redémarrer l'agent **après** avoir généré des events AD |
| Kafka connection refusée | Port 9092 bloqué depuis WEC | Vérifier firewall et `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| `channel 'ForwardedEvents' not found` | Souscription WEF inactive | Vérifier `Get-WinEvent -LogName ForwardedEvents` sur WEC01 |

---

## Variables d'environnement — référence complète

| Variable | Obligatoire | Défaut | Description |
|---|---|---|---|
| `IAP_INSTANCE_ID` | Non | `agent-default` | Identifiant de l'instance (logs) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | **Oui** | — | Adresse(s) du broker Kafka |
| `IAP_AGENT_ADAPTERS_WEF_ENABLED` | Non | `false` | Active l'adapter WEF |
| `IAP_WEF_SOURCE_ID` | Si WEF actif | `ad://change.me` | Identifiant de la source AD (ex: `ad://corp.client.com`) |
| `IAP_WEF_REMOTE_HOST` | Non | *(vide = local)* | Hôte WEC distant (laisser vide si agent sur WEC) |
| `IAP_CONFIG_FILE` | Non | *(vide)* | Chemin vers le fichier de règles YAML |
| `IAP_LOG_LEVEL` | Non | `INFO` | Niveau de log (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL` | Non | `PLAINTEXT` | `SSL` pour TLS |
| `SPRING_KAFKA_SSL_TRUST_STORE_LOCATION` | Si TLS | — | Chemin truststore JKS |
| `SPRING_KAFKA_SSL_TRUST_STORE_PASSWORD` | Si TLS | — | Mot de passe truststore |
| `SPRING_KAFKA_SSL_KEY_STORE_LOCATION` | Si TLS | — | Chemin keystore JKS |
| `SPRING_KAFKA_SSL_KEY_STORE_PASSWORD` | Si TLS | — | Mot de passe keystore |
