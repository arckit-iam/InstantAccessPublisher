# arc-kubeorchestrator

Dépôt centralisé des configurations Kubernetes (Helm charts + ressources Flux) pour tous les microservices de la plateforme InstantAccessProvider.

## Objectif

Séparer les configurations d'infrastructure (K8s / Helm / Flux) du code applicatif.  
Les commits de mise à jour de tags d'images (bumps de version) se font **ici**, pas dans les dépôts applicatifs.

## Structure

```
arc-kubeorchestrator/
├── arc-agent-ad/           # Helm chart arc-agent-ad
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
├── arc-consolidator/       # Helm chart arc-consolidator
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
├── arc-gateway/            # Helm chart arc-gateway
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
├── arc-publisher/          # Helm chart arc-publisher
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── templates/
│   └── postgres/           # Ressources PostgreSQL (StatefulSet, PVC, etc.)
└── flux/                   # Ressources Flux CD
    ├── gitrepo.yaml        # GitRepository unique → arc-kubeorchestrator
    ├── arc-agent-ad/
    ├── arc-consolidator/
    ├── arc-gateway/
    └── arc-publisher/
```

## Flux CD

Un seul objet `GitRepository` (dans `flux/gitrepo.yaml`) pointe vers ce dépôt.  
Chaque service a sa `Kustomization` et son `HelmRelease` dans `flux/<service>/`.

### Bootstrap

Appliquer manuellement la première fois :
```bash
kubectl apply -f flux/gitrepo.yaml
kubectl apply -f flux/arc-publisher/
kubectl apply -f flux/arc-consolidator/
kubectl apply -f flux/arc-gateway/
kubectl apply -f flux/arc-agent-ad/
```

## Mise à jour d'un tag d'image

Modifier la valeur `image.tag` dans le fichier `flux/<service>/helmrelease.yaml` correspondant, puis pousser le commit.  
Flux CD détectera le changement et redéploiera automatiquement.

## Documentation

| Document | Contenu |
|---|---|
| [docs/DEPLOY.md](docs/DEPLOY.md) | Guide de déploiement à destination des intégrateurs |
| [docs/CI-CD.md](docs/CI-CD.md) | Pipelines d'intégration et de déploiement, bump des tags |

La documentation d'architecture et de conception de la plateforme est dans le dépôt [arc-docs](https://github.com/arckit-iam/arc-docs).

## Licence

Copyright © 2026 Dédale Solutions

Licensed under the EUPL

- Charts Helm et ressources Flux : [EUPL-1.2](LICENSES/EUPL-1.2.txt).
- Documentation (`README.md`, `docs/`) : [CC-BY-4.0](LICENSES/CC-BY-4.0.txt).
