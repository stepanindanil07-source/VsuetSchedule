#!/usr/bin/env bash
# One database backup: pg_dump in PostgreSQL's custom format (compressed, restorable with pg_restore), checked to be
# readable, written atomically, with old backups removed. Connection settings come from the PG* environment variables.
set -euo pipefail

dir="${BACKUP_DIR:-/backups}"
keep_days="${BACKUP_RETENTION_DAYS:-14}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
partial="$dir/.vsuet-$stamp.dump.partial"
final="$dir/vsuet-$stamp.dump"

mkdir -p "$dir"
# a half-written file must never be mistaken for a backup
trap 'rm -f -- "$partial"' EXIT

pg_dump --format=custom --no-owner --no-privileges > "$partial"
# a file that cannot even be listed is not a backup: fail loudly instead of keeping it
pg_restore --list "$partial" > /dev/null
mv "$partial" "$final"

# only after a good new backup exists are old ones removed
find "$dir" -maxdepth 1 -name 'vsuet-*.dump' -mtime +"$keep_days" -delete

echo "backup written: $final ($(du -h "$final" | cut -f1)); keeping ${keep_days} days"
