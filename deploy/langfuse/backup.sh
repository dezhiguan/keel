#!/bin/sh
# 把 Langfuse 的 Postgres 和 ClickHouse 数据目录打成当天的压缩包。
# 对象存储上传由部署机器上的 ossutil / rclone 完成，密钥不进仓库。
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
out=${BACKUP_DIR:-/var/backups/langfuse}
stamp=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$out"
docker compose -f "$root/docker-compose.yml" exec -T postgres \
  pg_dump -U "${POSTGRES_USER:-postgres}" "${POSTGRES_DB:-postgres}" \
  | gzip > "$out/postgres-$stamp.sql.gz"
docker compose -f "$root/docker-compose.yml" exec -T clickhouse \
  clickhouse-client --query "BACKUP DATABASE default TO File('backup-$stamp')"
docker compose -f "$root/docker-compose.yml" cp \
  "clickhouse:/var/lib/clickhouse/backups/backup-$stamp" "$out/clickhouse-$stamp"
echo "wrote $out/postgres-$stamp.sql.gz and $out/clickhouse-$stamp"
