# .copilot-instructions.md

# Copilot Instructions for InstantAccessProvider

## Objectif du projet
Ce projet vise à fournir un accès instantané à des ressources via un agent, avec une architecture modulaire et des intégrations variées (Kafka, Redpanda, Active Directory, etc.).

## Directives générales
- Privilégier la clarté, la robustesse et la maintenabilité du code.
- Respecter les conventions de nommage propres à chaque langage (Java, Go, YAML, etc.).
- Documenter les fonctions, classes et modules importants.
- Préférer l’injection de dépendances et la configuration externe (fichiers YAML, variables d’environnement).
- Utiliser des tests unitaires et d’intégration pour valider les fonctionnalités critiques.

## Java (agent/)
- Utiliser Spring Boot et les bonnes pratiques associées (Services, Repositories, Controllers).
- Centraliser la configuration dans `application.yml`.
- Les tests doivent être placés dans `src/test/java` et suivre la convention `*Test.java`.
- Les logs doivent être structurés et exploitables (niveau INFO, WARN, ERROR).


## Documentation
- Toute nouvelle fonctionnalité doit être documentée dans le dossier `docs/` ou `doc-dev/`.
- Les exemples d’utilisation et guides d’intégration sont à placer dans `doc-integrator/`.

## Sécurité
- Ne jamais committer de secrets ou mots de passe.
- Utiliser des variables d’environnement ou des solutions de vault pour la gestion des secrets.

## Revue de code
- Toute contribution doit faire l’objet d’une revue de code.
- Les pull requests doivent décrire clairement les changements apportés.

---

Adaptez ces instructions selon l’évolution du projet et les besoins spécifiques de l’équipe.
