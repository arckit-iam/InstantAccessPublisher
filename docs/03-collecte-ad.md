# 03 — Collecte des Logs Active Directory

## 1. Événements Windows ciblés

Le **Security Event Log** Windows est la source principale. L'audit doit être activé sur tous les Domain Controllers.

### 1.1 Activation de l'audit

```powershell
# Vérifier l'état actuel
auditpol /get /category:"Account Management"

# Activer l'audit (succès + échec) via GPO ou directement
auditpol /set /subcategory:"User Account Management" /success:enable /failure:enable
auditpol /set /subcategory:"Security Group Management" /success:enable /failure:enable
auditpol /set /subcategory:"Distribution Group Management" /success:enable /failure:enable
auditpol /set /subcategory:"Other Account Management Events" /success:enable /failure:enable
```

La configuration recommandée est via **GPO** : `Computer Configuration > Windows Settings > Security Settings > Advanced Audit Policy Configuration > Account Management`

### 1.2 Tableau des Event IDs

#### Gestion des comptes utilisateurs

| Event ID | Nom | Déclencheur | Champs clés |
|---|---|---|---|
| **4720** | A user account was created | Création de compte | `TargetUserName`, `TargetDomainName`, `SubjectUserName`, `SubjectLogonId` |
| **4722** | A user account was enabled | Activation | `TargetUserName`, `SubjectUserName` |
| **4723** | An attempt was made to change an account's password | Changement MDP | `TargetUserName` |
| **4724** | An attempt was made to reset an account's password | Reset MDP | `TargetUserName`, `SubjectUserName` |
| **4725** | A user account was disabled | Désactivation | `TargetUserName`, `SubjectUserName` |
| **4726** | A user account was deleted | Suppression | `TargetUserName`, `SubjectUserName` |
| **4738** | A user account was changed | Modification (attributs) | `TargetUserName`, champs modifiés |
| **4781** | The name of an account was changed | Renommage | `OldTargetUserName`, `NewTargetUserName` |

#### Gestion des groupes de sécurité

| Event ID | Nom | Type groupe | Action |
|---|---|---|---|
| **4727** | A security-enabled global group was created | Sécurité / Global | Création |
| **4728** | A member was added to a security-enabled global group | Sécurité / Global | Ajout membre |
| **4729** | A member was removed from a security-enabled global group | Sécurité / Global | Retrait membre |
| **4730** | A security-enabled global group was deleted | Sécurité / Global | Suppression |
| **4731** | A security-enabled local group was created | Sécurité / Local | Création |
| **4732** | A member was added to a security-enabled local group | Sécurité / Local | Ajout membre |
| **4733** | A member was removed from a security-enabled local group | Sécurité / Local | Retrait membre |
| **4734** | A security-enabled local group was deleted | Sécurité / Local | Suppression |
| **4754** | A security-enabled universal group was created | Sécurité / Universel | Création |
| **4756** | A member was added to a security-enabled universal group | Sécurité / Universel | Ajout membre |
| **4757** | A member was removed from a security-enabled universal group | Sécurité / Universel | Retrait membre |
| **4758** | A security-enabled universal group was deleted | Sécurité / Universel | Suppression |

#### Groupes de distribution (pour contexte Exchange/M365)

| Event ID | Action |
|---|---|
| **4749** | Création groupe distribution global |
| **4751** | Ajout membre à groupe distribution global |
| **4753** | Suppression groupe distribution global |
| **4759** | Création groupe distribution universel |
| **4761** | Ajout membre à groupe distribution universel |

### 1.3 Exemple de log brut (Event ID 4728)

```xml
<Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
  <System>
    <EventID>4728</EventID>
    <TimeCreated SystemTime="2026-04-02T14:32:11.123456789Z"/>
    <Computer>DC-01.corp.example.com</Computer>
    <Channel>Security</Channel>
  </System>
  <EventData>
    <Data Name="MemberName">CN=john.doe,OU=Users,DC=corp,DC=example,DC=com</Data>
    <Data Name="MemberSid">S-1-5-21-XXXXXXXX-YYYYYYYY-ZZZZZZZZ-1105</Data>
    <Data Name="TargetUserName">Domain Admins</Data>
    <Data Name="TargetDomainName">CORP</Data>
    <Data Name="TargetSid">S-1-5-21-XXXXXXXX-YYYYYYYY-ZZZZZZZZ-512</Data>
    <Data Name="SubjectUserName">admin.service</Data>
    <Data Name="SubjectDomainName">CORP</Data>
    <Data Name="SubjectLogonId">0x3E7</Data>
    <Data Name="PrivilegeList">-</Data>
  </EventData>
</Event>
```

---

## 2. Mécanismes de collecte

### 2.1 Windows Event Forwarding (WEF) — Mode retenu

**Principe** : Le serveur WEC (Windows Event Collector) collecte les événements depuis les DCs via WS-Management. Deux modes existent :

| Mode | Qui initie ? | Prérequis côté DC | Usage |
|---|---|---|---|
| **SourceInitiated** | DC → WEC | GPO SubscriptionManager | Multiples DCs, scalable |
| **CollectorInitiated** | WEC → DC | WinRM actif sur DC, WEC$ dans Event Log Readers | Simple, 1–few DCs |

> **Mode retenu dans ce projet : CollectorInitiated** (cf. §6 — Retour d'expérience).

```
WEC Server ──WS-Management──► DC-01 (port 5985)
           ──WS-Management──► DC-02
           ──WS-Management──► DC-0N
           ──────────────────────────► ForwardedEvents ──► Agent ──► Kafka
```

**Configuration WEC — subscription XML retenue (`CollectorInitiated`)** :
```xml
<Subscription xmlns="http://schemas.microsoft.com/2006/03/windows/events/subscription">
  <SubscriptionId>IAM-Collector</SubscriptionId>
  <SubscriptionType>CollectorInitiated</SubscriptionType>
  <Enabled>true</Enabled>
  <Uri>http://schemas.microsoft.com/wbem/wsman/1/windows/EventLog</Uri>
  <ConfigurationMode>Custom</ConfigurationMode>
  <Delivery Mode="Push">
    <Batching>
      <MaxItems>10</MaxItems>
      <MaxLatencyTime>5000</MaxLatencyTime>
    </Batching>
  </Delivery>
  <Query><![CDATA[
    <QueryList>
      <Query Id="0" Path="Security">
        <Select Path="Security">*[System[
            EventID=4720 or EventID=4722 or EventID=4725 or EventID=4726 or
            EventID=4727 or EventID=4728 or EventID=4729 or EventID=4730 or
            EventID=4731 or EventID=4732 or EventID=4733 or EventID=4734 or
            EventID=4738 or EventID=4754 or EventID=4756 or EventID=4757 or
            EventID=4758 or EventID=4781
        ]]</Select>
      </Query>
    </QueryList>
  ]]></Query>
  <EventSources>
    <EventSource Enabled="true">
      <Address>DC01.iap.local</Address>
    </EventSource>
  </EventSources>
  <ReadExistingEvents>false</ReadExistingEvents>
  <ContentFormat>Events</ContentFormat>
  <LogFile>ForwardedEvents</LogFile>
</Subscription>
```

> Le `ContentFormat=Events` transmet le XML brut de l'événement. NXLog/l'Agent Java parsent ce XML directement. `RenderedText` nécessite que les providers de sécurité AD soient présents sur WEC01, ce qui n'est pas le cas hors DC.

**Avantages WEF** :
- Natif Windows, aucun agent sur les DCs
- Résistant aux interruptions réseau (bufferisation locale)
- Contrôle fin via subscriptions XML
- Chiffrement Kerberos natif

**Inconvénients** :
- Latence potentielle (MaxLatencyTime configurable à 1–5s)
- WEC Server = SPOF si pas redondé

### 2.2 Winlogbeat (Alternative / complément)

Winlogbeat (Elastic) peut être déployé **sur le WEC** (ou directement sur chaque DC si toléré).

```yaml
# winlogbeat.yml
winlogbeat.event_logs:
  - name: ForwardedEvents
    event_id: 4720, 4722, 4725, 4726, 4727, 4728, 4729, 4730,
              4731, 4732, 4733, 4734, 4738, 4754, 4756, 4757,
              4758, 4781
    ignore_older: 72h
    forwarded: true

output.kafka:
  hosts: ["kafka-broker-1:9092", "kafka-broker-2:9092"]
  topic: "ad.raw.events"
  required_acks: -1
  compression: gzip
  version: "2.8.0"
  ssl.enabled: true
  ssl.certificate_authorities: ["/etc/pki/ca.crt"]
```

### 2.3 Service AD Watcher (Custom) — .NET / Java / Go

Un microservice dédié offre plus de contrôle : déduplication, enrichissement LDAP, gestion du schéma.

```csharp
// Exemple pseudo-code C# — Lecture WEF et publication Kafka
public class AdWatcherService : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        var query = new EventLogQuery("ForwardedEvents",
            PathType.LogName,
            "*[System[EventID=4720 or EventID=4728 or EventID=4727]]");

        using var watcher = new EventLogWatcher(query);
        watcher.EventRecordWritten += OnEventReceived;
        watcher.Enabled = true;

        await Task.Delay(Timeout.Infinite, ct);
    }

    private async void OnEventReceived(object sender, EventRecordWrittenEventArgs e)
    {
        var record = e.EventRecord;
        var rawEvent = ParseEvent(record);

        // Déduplication par RecordId + ComputerName
        if (await _deduplicationCache.ExistsAsync(rawEvent.DeduplicationKey))
            return;

        // Enrichissement LDAP
        var enriched = await _ldapEnricher.EnrichAsync(rawEvent);

        // Publication Kafka
        await _kafkaProducer.ProduceAsync("ad.raw.events", enriched);
    }
}
```

### 2.4 DirSync LDAP (Fallback / Réconciliation)

Le protocole **DirSync** (contrôle LDAP `1.2.840.113556.1.4.841`) permet de détecter tous les changements d'attributs LDAP depuis un cookie de synchronisation.

```python
# Exemple Python — DirSync polling
import ldap3
from ldap3.protocol.rfc4511 import LDAP_MAX_INT

server = ldap3.Server('ldap://dc-01.corp.example.com', use_ssl=True)
conn = ldap3.Connection(server, user='CN=svc-dirsync,...', password='...',
                        authentication=ldap3.NTLM)
conn.bind()

dirsync_control = ldap3.protocol.microsoft.DirSyncRequestControl(
    flags=ldap3.DIRSYNC_OBJECT_SECURITY,
    cookie=b''  # cookie stocké entre les runs
)

while True:
    conn.search(
        search_base='DC=corp,DC=example,DC=com',
        search_filter='(|(objectClass=user)(objectClass=group))',
        attributes=['*', '+'],
        controls=[dirsync_control]
    )

    for entry in conn.entries:
        process_ldap_change(entry)

    # Sauvegarder le cookie pour garantir aucune perte
    save_cookie(dirsync_control.cookie)

    if not dirsync_control.more_results:
        time.sleep(10)
```

**Avantages DirSync** :
- Garantit la détection même si l'Event Log est plein ou a été effacé
- Fournit les attributs complets de l'objet modifié
- Cookie persistant = reprise sans perte après redémarrage

**Inconvénients** :
- Légèrement moins temps-réel que WEF (polling 10–30s)
- Requiert un compte avec privilège `DS-Replication-Get-Changes`

---

## 3. Déduplication

Un même changement peut être enregistré par plusieurs DCs (réplication AD). La déduplication se fait sur :

```
DeduplicationKey = SHA256(EventID + TargetSid + SubjectLogonId + Date(truncate_to_second))
```

Implémentation : **Redis** avec TTL 60 secondes (ou table d'état Kafka Streams).

---

## 4. Résilience et non-perte d'événements

```
DC ──► WEF Buffer local ──► WEC Server ──► Queue locale ──► Kafka
              ↑                   ↑              ↑
         (si perte réseau,   (si WEC down,   (si Kafka
          buffer local)       buffer WEC)     unavailable,
                                              buffer disque)
```

- WEF : bufferisation locale sur chaque DC si le WEC est inaccessible (plusieurs heures)
- AD Watcher : file d'attente en mémoire / disque avec retry exponentiel vers Kafka
- DirSync : le cookie garantit la reprise depuis le dernier état connu

---

## 5. Architecture technique — Déploiement WEF + Collecteur + Agent

### 5.1 Vue d'ensemble

L'architecture de collecte AD repose sur trois niveaux distincts, chacun dans sa zone de sécurité :

```
[DC (Tier0)] ──WEF──► [Collecteur Windows (Tier1)] ──► [Agent Java (Ubuntu, Tier1)] ──► [Topic Kafka]
[ISDS]       ──Syslog──────────────────────────────────► [Agent Java (Ubuntu, Tier1)] ──► [Topic Kafka]
[Autres]     ──Syslog/API──────────────────────────────► [Agent Java (Ubuntu, Tier1)] ──► [Topic Kafka]
```

> **Règle fondamentale** : Ubuntu/Linux ne peut pas être un serveur WEC. WEF exige un collecteur Windows. L'Agent Java ne tourne donc pas sur le Collecteur Windows, mais sur un serveur Ubuntu distinct vers lequel les logs sont transférés.

### 5.2 Contraintes techniques

| Composant | Peut recevoir WEF ? | Peut être serveur WEC ? |
|---|---|---|
| Windows Server 2019/2022/2025 | ✔ Oui | ✔ Oui |
| Ubuntu / Linux | ✗ Non | ✗ Non |

👉 Le Collecteur Windows est **obligatoire** comme étape intermédiaire entre les DCs Tier0 et l'Agent Java Ubuntu.

### 5.3 Rôle du Collecteur Windows (Tier1)

Le **Collecteur WEF** (Windows Server 2025, membre du domaine, OU Tier1) :
- Reçoit les événements WEF de tous les DCs via WS-Management (port 5985)
- Stocke les événements dans le journal `ForwardedEvents`
- Sert de tampon sécurisé entre Tier0 (DCs) et le reste de l'infrastructure
- Transfère les événements vers l'Agent Java Ubuntu via **Filebeat**, NXLog ou une API directe

**Spécifications recommandées :**

| Ressource | Valeur |
|---|---|
| OS | Windows Server 2025 |
| vCPU | 2 |
| RAM | 4 Go |
| Disque | 40 Go |
| Réseau | Membre du domaine, OU Tier1 |

### 5.4 Configuration WEF côté DCs

#### Activer WinRM sur les DCs

```powershell
winrm quickconfig
```

#### Activer le service Event Collector (WEC)

```powershell
Set-Service Wecsvc -StartupType Automatic
Start-Service Wecsvc
```

#### GPO : `Tier0-WEF-Forwarding`

Chemin GPO :
```
Computer Configuration
  → Policies
    → Administrative Templates
      → Windows Components
        → Event Forwarding
          → Configure target Subscription Manager
```

Valeur à définir :
```
Server=http://WEF-COLLECTOR.corp.example.com:5985/wsman/SubscriptionManager/WEC,Refresh=60
```

Lier cette GPO à l'OU contenant les DCs Tier0.

### 5.5 Configuration du Collecteur Windows

#### Initialiser le collecteur

```powershell
wecutil qc
```

#### Créer la subscription — `C:\Subscriptions\AD-Audit.xml`

```xml
<Subscription xmlns="http://schemas.microsoft.com/2006/03/windows/events/subscription">
  <SubscriptionId>AD-Audit</SubscriptionId>
  <SubscriptionType>SourceInitiated</SubscriptionType>
  <Description>Audit AD : création/modif comptes, groupes, Kerberos</Description>
  <Enabled>true</Enabled>
  <Uri>http://schemas.microsoft.com/wbem/wsman/1/windows/EventLog</Uri>
  <ConfigurationMode>Custom</ConfigurationMode>
  <Delivery Mode="Push">
    <Batching>
      <MaxItems>50</MaxItems>
      <MaxLatencyTime>30</MaxLatencyTime>
    </Batching>
  </Delivery>
  <Query>
    <![CDATA[
      <QueryList>
        <Query Id="0" Path="Security">
          <Select Path="Security">
            *[System[
              EventID=4624 or EventID=4625 or EventID=4672 or
              EventID=4720 or EventID=4722 or EventID=4723 or EventID=4724 or
              EventID=4725 or EventID=4726 or EventID=4727 or EventID=4728 or
              EventID=4729 or EventID=4732 or EventID=4733 or EventID=4735 or
              EventID=4738 or EventID=4740 or EventID=4767 or EventID=4768 or
              EventID=4769 or EventID=4771
            ]]
          </Select>
        </Query>
        <Query Id="1" Path="Directory Service">
          <Select Path="Directory Service">*</Select>
        </Query>
      </QueryList>
    ]]>
  </Query>
  <ReadExistingEvents>false</ReadExistingEvents>
  <TransportName>HTTP</TransportName>
  <ContentFormat>RenderedText</ContentFormat>
  <Locale Language="fr-FR"/>
  <LogFile>ForwardedEvents</LogFile>
  <AllowedSourceDomainComputers>O:NSG:NSD:(A;;GA;;;DC)(A;;GA;;;NS)</AllowedSourceDomainComputers>
</Subscription>
```

Importer la subscription :

```powershell
wecutil cs C:\Subscriptions\AD-Audit.xml
```

Vérifier la réception :

```powershell
Get-WinEvent -LogName ForwardedEvents -MaxEvents 20
```

### 5.6 Transfert vers l'Agent Java (Ubuntu)

Le Collecteur Windows envoie les événements depuis `ForwardedEvents` vers l'Agent Java Ubuntu. Trois options :

#### Option A — Filebeat (recommandé pour démarrage rapide)

```yaml
# filebeat.yml — sur le Collecteur Windows
filebeat.inputs:
  - type: winlog
    event_logs:
      - name: ForwardedEvents
        ignore_older: 24h

output.logstash:
  hosts: ["ubuntu-agent.corp.example.com:5044"]
```

```
ForwardedEvents → Filebeat (Windows) → Logstash/Agent (Ubuntu) → Kafka
```

#### Option B — NXLog

```
ForwardedEvents → NXLog (Windows) → UDP/TCP Syslog → SyslogAdapter (Agent Java)
```

Configurer NXLog pour envoyer en Syslog RFC 5424 TCP vers le port d'écoute `SyslogAdapter` de l'Agent Java (ex : 6514).

#### Option C — WEF Adapter direct (Java, roadmap)

L'`WefAdapter` lit directement le journal `ForwardedEvents` via les API Windows (JNA). Dans ce cas, l'Agent Java tourne **sur le Collecteur Windows** (JVM Windows), sans Ubuntu intermédiaire.

```
ForwardedEvents → WefAdapter (Java/JNA, tourne sur Windows) → Kafka
```

> Ce mode est en cours d'implémentation (`WefAdapter.java`). Il supprime l'intermédiaire Filebeat mais nécessite un JRE sur le Collecteur Windows.

### 5.7 Intégration ISDS (IBM Security Directory Server)

ISDS ne supporte pas WEF. Il expose ses événements via :

| Protocole | Détails |
|---|---|
| **Syslog** TCP/UDP/TLS | Format BSM ou CEF — récepteur : `SyslogAdapter` de l'Agent |
| **Audit LDAP** | Requêtes LDAP vers le journal d'audit ISDS |
| **Fichiers journaux** | Collecte de fichiers `/var/log/...` via agent de fichiers |
| **SNMP traps** | Alertes opérationnelles (moins pertinent pour IAM) |

Architecture ISDS → Kafka :

```
ISDS → Syslog (TCP 6514) → SyslogAdapter (Agent Java Ubuntu) → iam.raw.isds
```

### 5.8 Architecture finale complète

```
                ┌─────────────────────┐
                │   DC-01 (Tier0)     │
                │   DC-02 (Tier0)     │
                │   DC-0N (Tier0)     │
                └──────────┬──────────┘
                           │ WEF (WS-Management port 5985)
                           ▼
                ┌─────────────────────┐
                │  Collecteur WEF     │  ← Windows Server, Tier1
                │  (WEC Server)       │    Journal: ForwardedEvents
                └──────────┬──────────┘
                           │ Filebeat / NXLog / WefAdapter (Java)
                           ▼
                ┌─────────────────────┐
                │   Agent Java        │  ← Ubuntu, Tier1
                │   (IAP Agent)       │    SyslogAdapter / WefAdapter
                │                     │    EventCollectionUseCase
                └──────────┬──────────┘
                           │
              ┌────────────┴─────────────┐
              ▼                          ▼
   iam.raw.ad-corp-example-com    iam.raw.isds
        (Topics Kafka)

ISDS ──Syslog TCP 6514──────────────► Agent Java (SyslogAdapter) → iam.raw.isds
Autres sources ──Syslog/Webhook────► Agent Java → iam.raw.{sourceId}
```

**Flux de sécurité par zone :**

| Zone | Composant | Protocole sortant |
|---|---|---|
| Tier0 | Domain Controllers | WEF → port 5985 vers Collecteur Tier1 |
| Tier1 (Windows) | Collecteur WEF | Filebeat/NXLog → port 5044/6514 vers Agent Ubuntu |
| Tier1 (Linux) | Agent Java Ubuntu | Kafka Producer → port 9092 vers Kafka |
| Tier1 (Linux) | Agent Java Ubuntu | Accepte Syslog sur port 6514 depuis ISDS |

---

## 6. Retour d'expérience — Mise en œuvre IAP Lab (Avril 2026)

### 6.1 Topologie de l'environnement de lab

```
┌─────────────────────────────────────────────────────────────────┐
│                    Réseau ZeroTier (VPN overlay)                 │
│                    Réseau : 192.168.10.0/24                      │
│                    Network ID : f3797ba7a8f422c0                 │
│                                                                  │
│   ┌─────────────────────┐         ┌─────────────────────┐       │
│   │  DC01.iap.local     │         │  WEC01.iap.local    │       │
│   │  Windows Server     │◄────────│  Windows Server     │       │
│   │  2025 build 26100   │  WEF    │  2022 build 20348   │       │
│   │  (OVH, Tier0)       │  port   │  (Tier1)            │       │
│   │  IP: 192.168.10.10  │  5985   │  IP: 192.168.10.20  │       │
│   │  Rôle: DC, DNS      │         │  Rôle: WEC          │       │
│   └─────────────────────┘         └──────────┬──────────┘       │
│                                              │                   │
│                                   ForwardedEvents                │
│                                              │                   │
│                                              ▼ (à venir)         │
│                                   ┌─────────────────────┐       │
│                                   │  NXLog / Agent Java │       │
│                                   │  → Kafka            │       │
│                                   └─────────────────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

**ZeroTier** est utilisé comme VPN overlay entre les deux serveurs OVH hébergés dans des datacenters distincts. Il crée une interface réseau virtuelle (`ZeroTier One`) sur chaque machine, leur permettant de communiquer comme si elles étaient sur le même LAN, sans configuration firewall complexe.

| Machine | OS | IP ZeroTier | Rôle |
|---|---|---|---|
| DC01.iap.local | Windows Server 2025 (26100, OVH) | 192.168.10.10 | Domain Controller, DNS, source WEF |
| WEC01.iap.local | Windows Server 2022 (20348, OVH) | 192.168.10.20 | Windows Event Collector, futur hôte NXLog |

---

### 6.2 Problèmes rencontrés et solutions

#### Problème 1 — WinRM absent sur DC01 (build OVH 26100)

**Symptôme** : `Start-Service WinRM` → `System error 2 — The system cannot find the file specified`. Le composant WinRM était absent de WinSxS sur l'image OVH.

**Solution** :
1. Monter l'ISO Windows Server 2025 Evaluation comme lecteur D: sur DC01
2. Restaurer les composants manquants via DISM :
   ```powershell
   DISM /Online /Cleanup-Image /RestoreHealth /Source:D:\sources\install.wim:2 /LimitAccess
   ```
3. Reconstruire manuellement les clés registre WSMAN manquantes :
   - Extraire le hive SYSTEM de l'image WIM via une tâche planifiée SYSTEM
   - Copier les clés `HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WSMAN`
   - Corriger les chemins de plugins (`%windir%\System32\...`)
   - Ajouter le groupe `WSMAN` dans `HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Svchost`
   - Recréer l'URL ACL HTTP pour WinRM :
     ```cmd
     netsh http add urlacl url=http://+:5985/wsman/ sddl="D:(A;;GX;;;S-1-5-80-569256582-2953403351-2909559716-1301513147-412116970)(A;;GX;;;S-1-5-80-4059739203-877974739-1245360912-527174227-2996563517)"
     ```

**Résultat** : `Test-WSMan DC01.iap.local` retourne avec succès.

---

#### Problème 2 — wecutil cassé sur WEC01 (Server 2022)

**Symptôme** : `wecutil cs subscription.xml` → `0x3aed — No active channel is found for the query`, même pour `wecutil ?`.

**Causes identifiées** :
- `wecutil cs` valide le XPath de la query **localement** contre le registre `WINEVT\Channels`. Le canal `Security` n'est pas enregistré dans ce registre sur WEC01 (canal kernel-managed).
- Le plugin WinRM `Event Collector` était absent (non créé par `wecutil qc` qui était lui-même cassé).
- Le SDDL du service `Wecsvc` n'incluait pas `NETWORK SERVICE` ni `Domain Computers`.

**Solution appliquée** :

```powershell
# 1. Ajouter le canal Security dans le registre WINEVT (satisfait la validation wecutil)
$cPath = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WINEVT\Channels\Security"
New-Item -Path $cPath -Force
Set-ItemProperty $cPath -Name "Enabled"     -Value 1 -Type DWord
Set-ItemProperty $cPath -Name "Type"        -Value 1 -Type DWord
Set-ItemProperty $cPath -Name "Isolation"   -Value 2 -Type DWord
Set-ItemProperty $cPath -Name "LogFileName" -Value "%SystemRoot%\System32\Winevt\Logs\Security.evtx" -Type ExpandString

# 2. Corriger le SDDL de Wecsvc (ajouter NS et Domain Computers)
sc.exe sdset wecsvc "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWLOCRRC;;;IU)(A;;CCLCSWLOCRRC;;;SU)(A;;CCLCSWRPWPDTLOCRRC;;;NS)(A;;CCLCSWRPWPDTLOCRRC;;;S-1-5-21-984562994-3965904818-3631805304-515)S:(AU;FA;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;WD)"

# 3. Exécuter wecutil qc (maintenant que Wecsvc accepte les connexions)
wecutil qc /q

# 4. Corriger le SDDL du plugin WinRM Event Collector (wecutil qc met des ACEs insuffisantes)
$regPath = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WSMAN\Plugin\Event Collector"
$xml = (Get-ItemProperty -Path $regPath).ConfigXML
$newSddl = "O:NSG:BAD:P(A;;GA;;;BA)(A;;GA;;;S-1-5-21-984562994-3965904818-3631805304-515)(A;;GA;;;NS)(A;;GA;;;S-1-5-11)S:P(AU;FA;GA;;;WD)(AU;SA;GXGW;;;WD)"
$newXml = $xml -replace 'Sddl="[^"]*"', "Sddl=`"$newSddl`""
Set-ItemProperty -Path $regPath -Name "ConfigXML" -Value $newXml -Type String

# 5. Corriger le RootSDDL WinRM (ajouter Authenticated Users pour network logon)
$currentSDDL = (Get-Item WSMan:\localhost\Service\RootSDDL).Value
$newSDDL = $currentSDDL -replace "S:P\(", "(A;;GR;;;S-1-5-11)S:P("
Set-Item WSMan:\localhost\Service\RootSDDL -Value $newSDDL -Force

Restart-Service WinRM -Force
```

**Note importante** : La clé `WINEVT\Channels\Security` est **effacée à chaque restart du service EventLog** (canal kernel-managed). Elle doit être recréée avant chaque `wecutil cs`. Pour automatiser, créer une tâche planifiée déclenchée sur l'événement de démarrage du service EventLog.

---

#### Problème 3 — Format CDATA requis pour la subscription XML

**Symptôme** : `wecutil cs` avec `<QueryList>` direct → `0x3aed` si `Path="Security"` sur `<Query>` (wecutil valide localement).

**Solution** : Encapsuler la QueryList dans un bloc `<![CDATA[...]]>` à l'intérieur d'un élément `<Query>`. wecutil ne parse pas le contenu CDATA pour validation. Le contenu est transmis tel quel à DC01 qui l'exécute localement sur son canal Security.

```xml
<Query><![CDATA[
  <QueryList>
    <Query Id="0" Path="Security">
      <Select Path="Security">*[System[EventID=4720 ...]]</Select>
    </Query>
  </QueryList>
]]></Query>
```

---

#### Problème 4 — Mode SourceInitiated bloqué (Access Denied error 5)

**Symptôme** : En mode `SourceInitiated`, DC01 contacte WEC01 mais reçoit `Access Denied` en continu (Event 105 dans `Microsoft-Windows-Forwarding/Operational`).

**Cause** : Le plugin WinRM `Event Collector` sur WEC01 refusait DC01$ avec GR (Generic Read). Le service `Wecsvc` refusait également la connexion (SDDL de service sans Domain Computers).

**Solution finale** : Passer en **mode `CollectorInitiated`** — WEC01 se connecte à DC01. Ce mode est plus simple dans ce contexte (1 DC, pas besoin de GPO SubscriptionManager) et DC01 doit seulement autoriser WEC01$ en lecture sur le Security log.

Sur DC01 :
```powershell
net localgroup "Event Log Readers" "IAP\WEC01$" /add
net localgroup "Event Log Readers" "NT AUTHORITY\NETWORK SERVICE" /add
```

---

### 6.3 Procédure de déploiement complète — Reproductible

Ordre strict à respecter pour un nouveau WEC :

```powershell
# === Sur DC01 ===
# Autoriser WEC01 à lire le Security log
net localgroup "Event Log Readers" "IAP\WEC01$" /add
net localgroup "Event Log Readers" "NT AUTHORITY\NETWORK SERVICE" /add

# === Sur WEC01 ===

# 1. Ajouter canal Security dans WINEVT (doit être fait AVANT wecutil qc et cs)
$cPath = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WINEVT\Channels\Security"
New-Item -Path $cPath -Force
Set-ItemProperty $cPath -Name "Enabled"     -Value 1   -Type DWord
Set-ItemProperty $cPath -Name "Type"        -Value 1   -Type DWord
Set-ItemProperty $cPath -Name "Isolation"   -Value 2   -Type DWord
Set-ItemProperty $cPath -Name "LogFileName" -Value "%SystemRoot%\System32\Winevt\Logs\Security.evtx" -Type ExpandString

# 2. Corriger SDDL Wecsvc (SID Domain Computers à adapter)
$dcSID = (New-Object Security.Principal.NTAccount("IAP\Domain Computers")).Translate([Security.Principal.SecurityIdentifier]).Value
sc.exe sdset wecsvc "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWLOCRRC;;;IU)(A;;CCLCSWLOCRRC;;;SU)(A;;CCLCSWRPWPDTLOCRRC;;;NS)(A;;CCLCSWRPWPDTLOCRRC;;;$dcSID)S:(AU;FA;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;WD)"

# 3. Initialiser Wecsvc
wecutil qc /q

# 4. Corriger plugin WinRM Event Collector
$regPath = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WSMAN\Plugin\Event Collector"
$xml = (Get-ItemProperty -Path $regPath).ConfigXML
$newSddl = "O:NSG:BAD:P(A;;GA;;;BA)(A;;GA;;;$dcSID)(A;;GA;;;NS)(A;;GA;;;S-1-5-11)S:P(AU;FA;GA;;;WD)(AU;SA;GXGW;;;WD)"
Set-ItemProperty -Path $regPath -Name "ConfigXML" -Value ($xml -replace 'Sddl="[^"]*"', "Sddl=`"$newSddl`"") -Type String

# 5. Corriger RootSDDL WinRM
$s = (Get-Item WSMan:\localhost\Service\RootSDDL).Value
Set-Item WSMan:\localhost\Service\RootSDDL -Value ($s -replace "S:P\(", "(A;;GR;;;S-1-5-11)S:P(") -Force

# 6. Redémarrer WinRM
Restart-Service WinRM -Force

# 7. Créer la subscription
# (Utiliser le fichier docs/wec-subscription-fixed.xml)
cmd.exe /c "wecutil cs C:\wec\IAM-fixed.xml"

# 8. Vérifier
wecutil gr IAM-Collector
```

---

### 6.4 Validation end-to-end

```powershell
# Sur DC01 — créer un utilisateur test (déclenche Event 4720)
$pwd = ConvertTo-SecureString "P@ssw0rd123!" -AsPlainText -Force
New-ADUser -Name "test-wef" -SamAccountName "test-wef" -AccountPassword $pwd -Enabled $true
Start-Sleep 15
Remove-ADUser -Identity "test-wef" -Confirm:$false

# Sur WEC01 — vérifier la réception
Get-WinEvent -LogName ForwardedEvents -MaxEvents 10 |
    Where-Object { $_.Id -in @(4720, 4726) } |
    Select-Object TimeCreated, Id, MachineName | Format-Table
```

Résultat attendu : Event 4720 (création) et 4726 (suppression) en provenance de `DC01.iap.local`.

---

### 6.5 Prochaines étapes — NXLog → Kafka

NXLog CE sera déployé sur WEC01 pour lire `ForwardedEvents` et publier vers Kafka :

```
ForwardedEvents (WEC01) → NXLog im_msvistalog → om_kafka → topic: iam.raw.ad
```

Configuration NXLog cible :

```
<Input ad_events>
    Module  im_msvistalog
    Query   <QueryList><Query Id="0"><Select Path="ForwardedEvents">*</Select></Query></QueryList>
</Input>

<Output kafka_out>
    Module  om_kafka
    BrokerList  kafka-broker:9092
    Topic   iam.raw.ad
</Output>

<Route ad_to_kafka>
    Path    ad_events => kafka_out
</Route>
```
