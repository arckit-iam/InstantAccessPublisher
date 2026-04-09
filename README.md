# INSTANTACCESSPUBLISHER

INSTANTACCESSPUBLISHER est une plateforme qui collecte, normalise et diffuse en temps réel les événements liés à la gestion des identités et des accès (IAM) provenant de différentes sources (Active Directory, applications, LDAP, Azure AD, etc.).

## À quoi ça sert ?
- Centraliser tous les événements d’identité et d’accès dans un format commun.
- Faciliter la détection d’anomalies de sécurité (SOC, SIEM).
- Synchroniser automatiquement les changements d’identités vers d’autres systèmes (ITSM, RH, Cloud, etc.).

## Pour qui ?
- Les équipes sécurité (SOC, RSSI) pour surveiller et réagir rapidement aux incidents.
- Les administrateurs IAM pour automatiser la gestion des droits et des accès.
- Les intégrateurs qui veulent connecter facilement de nouvelles sources ou cibles d’identités.

## Points forts
- Compatible avec de nombreuses sources (AD, LDAP, applications, cloud…)
- Modèle de données unifié et extensible
- Publication des événements sur un bus de messages (Kafka, Event Hub…)
- Pensé pour l’automatisation, la sécurité et l’évolutivité

Pour plus de détails techniques, voir la documentation dans le dossier `doc/`.
