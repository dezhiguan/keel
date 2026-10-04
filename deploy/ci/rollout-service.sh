#!/usr/bin/env bash
# 只发布一个服务。调用：rollout-service.sh <服务名> <清单> <占位符> [必需的 Secret...]
set -euo pipefail

service="${1:?service}"
manifest="${2:?manifest}"
placeholder="${3:?placeholder}"
shift 3

if [[ -z "${ACR_REGISTRY:-}" ]]; then
  echo "ACR_REGISTRY must be set" >&2
  exit 1
fi

tag="${GITHUB_SHA::12}"
pull="${ACR_REGISTRY%/}"
rendered="/tmp/${service}.yaml"
sed "s|${placeholder}|${pull}/${service}:${tag}|" "$manifest" > "$rendered"
if grep -q "PLACEHOLDER_" "$rendered"; then
  echo "镜像占位符未被替换" >&2
  exit 1
fi
scp -F ~/.ssh/config "$rendered" "keel-app:/tmp/${service}.yaml"
password_b64="$(printf '%s' "${ACR_PASSWORD}" | base64 -w0)"
required="$(printf '%s\n' "$@")"
ssh -T -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 keel-app \
  "ACR_REGISTRY='${ACR_REGISTRY}' ACR_USERNAME='${ACR_USERNAME}' ACR_PASSWORD_B64='${password_b64}' SERVICE='${service}' REQUIRED=$(printf '%q' "$required") bash -s" <<'EOF'
set -euo pipefail
ACR_PASSWORD="$(printf '%s' "${ACR_PASSWORD_B64}" | base64 -d)"
kubectl create namespace keel-system --dry-run=client -o yaml | kubectl apply -f -
while IFS= read -r name; do
  [[ -z "${name}" ]] && continue
  if ! kubectl -n keel-system get secret "${name}" >/dev/null 2>&1; then
    echo "缺少 Secret ${name}。按 deploy/README.md 在 Server 3 上建好再重跑。"
    exit 1
  fi
done <<< "${REQUIRED}"
kubectl -n keel-system create secret docker-registry acr-cred \
  --docker-server="${ACR_REGISTRY%%/*}" \
  --docker-username="${ACR_USERNAME}" \
  --docker-password="${ACR_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f "/tmp/${SERVICE}.yaml"
kubectl -n keel-system rollout status "deployment/${SERVICE}" --timeout=180s
rm -f "/tmp/${SERVICE}.yaml"
EOF
