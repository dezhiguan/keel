#!/bin/sh
# 数据盘使用率达到 80% 时退出码为 2，供 cron 或监控接告警。
set -eu
threshold=${DISK_ALERT_PERCENT:-80}
mount=${DISK_ALERT_MOUNT:-/}
used=$(df -P "$mount" | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
echo "mount=$mount used=${used}% threshold=${threshold}%"
if [ "$used" -ge "$threshold" ]; then
  echo "disk usage is at or above the threshold" >&2
  exit 2
fi
