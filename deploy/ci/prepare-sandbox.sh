#!/usr/bin/env bash
# Install the locked-down sandbox namespace and image pull credential on the app node.
set -euo pipefail

: "${ACR_REGISTRY:?ACR_REGISTRY must be set}"
: "${ACR_USERNAME:?ACR_USERNAME must be set}"
: "${ACR_PASSWORD:?ACR_PASSWORD must be set}"

scp -F ~/.ssh/config deploy/k3s/sandbox/namespace.yaml keel-app:/tmp/keel-sandbox-namespace.yaml
password_b64="$(printf '%s' "${ACR_PASSWORD}" | base64 -w0)"
ssh -T -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 keel-app \
  "ACR_REGISTRY='${ACR_REGISTRY}' ACR_USERNAME='${ACR_USERNAME}' ACR_PASSWORD_B64='${password_b64}' bash -s" <<'EOF'
set -euo pipefail
ACR_PASSWORD="$(printf '%s' "${ACR_PASSWORD_B64}" | base64 -d)"
kubectl apply -f /tmp/keel-sandbox-namespace.yaml
kubectl -n keel-devflow-sandbox create secret docker-registry acr-cred \
  --docker-server="${ACR_REGISTRY%%/*}" \
  --docker-username="${ACR_USERNAME}" \
  --docker-password="${ACR_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -
rm -f /tmp/keel-sandbox-namespace.yaml
EOF
