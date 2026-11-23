#!/usr/bin/env bash
# Runs ops/backup.sh now and then every BACKUP_INTERVAL_HOURS. A failed backup is logged and retried at the next interval;
# the container's health check (see compose.prod.yml) turns unhealthy when no backup is newer than two intervals.
set -uo pipefail

interval_hours="${BACKUP_INTERVAL_HOURS:-24}"
while true; do
  if ! /bin/bash /ops/backup.sh; then
    echo "BACKUP FAILED at $(date -u +%FT%TZ)" >&2
  fi
  sleep "$((interval_hours * 3600))"
done
