# Frontend Node + Arc Gateway Kerberos Guide

## Objective

Create a web frontend in Node.js while keeping Kerberos implementation only in arc-gateway.

## Security principle

- Do not manage keytab in frontend container.
- Do not run GSS/JAAS in frontend app.
- Browser handles SPNEGO challenge with gateway host.

## Flow

1. User opens frontend page from `https://arc-gateway.arckit.fr/`.
2. Frontend sends `fetch('/api/products', { credentials: 'include' })`.
3. Arc-gateway replies `401 WWW-Authenticate: Negotiate` if needed.
4. Browser sends Negotiate token.
5. Arc-gateway validates token with keytab and forwards request to backend service.

## K8s routing recommendation

Single host, path-based routing:

- Host: `arc-gateway.arckit.fr`
- Path `/api` to `arc-gateway` service
- Path `/` to `frontend-node` service

## Example Ingress snippet (nginx)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: arc-portal
  namespace: default
spec:
  rules:
    - host: arc-gateway.arckit.fr
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: arc-gateway
                port:
                  number: 8080
          - path: /
            pathType: Prefix
            backend:
              service:
                name: arc-gateway-frontend-node
                port:
                  number: 3000
```

## Browser policy notes

Set Negotiate allowlist for your domain via enterprise policy (Edge/Chrome) or trusted intranet zone settings.

## Validation checklist

- Open frontend URL with domain principal.
- Trigger GET and POST from UI.
- Confirm gateway logs show authenticated principal.
- Confirm backend logs receive expected paths after gateway filters.
