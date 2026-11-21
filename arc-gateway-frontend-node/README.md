# Arc Gateway Frontend Node

Minimal Node.js frontend for arc-gateway. The UI calls `/api/*` and relies on browser SPNEGO negotiation against `arc-gateway.arckit.fr`.

## 1) Prerequisites

- Node.js 20+
- User workstation joined to AD (or valid Kerberos setup)
- Browser configured to allow Negotiate on `arc-gateway.arckit.fr`
- A valid TGT on client machine (`klist`)

## 2) Install and run

```bash
cd frontend-node
npm install
npm start
```

Server starts on `http://localhost:3000`.

## 3) Kerberos model

Important: this Node app does not implement Kerberos itself.

- Kerberos/SPNEGO happens between browser and API Gateway.
- Frontend performs `fetch('/api/...', { credentials: 'include' })`.
- Gateway validates Negotiate token using its own keytab.

## 4) Recommended production topology

Use same host for UI and API Gateway: `arc-gateway.arckit.fr`.

- `/api` -> `arc-gateway` service
- `/` -> `frontend-node` service

This keeps same-origin requests and avoids CORS complexity.

## 5) Local test command (gateway)

Example request validated in your setup:

```bash
curl -v --negotiate -u : --resolve arc-gateway.arckit.fr:18083:127.0.0.1 \
  -H "Host: arc-gateway.arckit.fr" \
  http://arc-gateway.arckit.fr:18083/api/products
```

## 6) Docker

```bash
docker build -t arc-gateway-frontend-node:local .
docker run --rm -p 3000:3000 arc-gateway-frontend-node:local
```

## 7) Kubernetes / Helm / Flux

This repository now follows the same GitOps layout as other InstantAccessProvider services.

- Helm chart: `k8s/Chart.yaml`, `k8s/values.yaml`, `k8s/templates/*`
- Flux resources: `k8s/flux/*`

Key routing in chart ingress:

- `/api` -> `arc-gateway` service (`8080`)
- `/` -> `arc-gateway-frontend-node` service (`3000`)

Manual bootstrap (if needed):

```bash
kubectl apply -f k8s/flux/
```

## 8) GitHub Workflows

- `build.yml`: build/push image + bump HelmRelease to `VERSION-dev`
- `promote.yml`: promote `dev -> rc -> stable/latest`
- `deploy.yml`: apply Flux manifests from `k8s/flux/`
- `bump-helmrelease.yml`: open PR to bump HelmRelease to release version

## 9) Troubleshooting

- `401` from `/api/*`: browser did not send Negotiate token, or no TGT.
- `404` from `/api/*`: backend route exists in gateway but target microservice endpoint is missing.
- `CORS` errors: requests are cross-origin. Prefer same host deployment.

## License

Copyright © 2026 Dédale Solutions

Licensed under the [EUPL-1.2](LICENSE) (European Union Public Licence v. 1.2).
