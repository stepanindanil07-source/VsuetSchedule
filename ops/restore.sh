#!/usr/bin/env bash
# Restores a backup made by backup.sh into the database named by the PG* environment variables, REPLACING what is there
# (one transaction: a failed restore leaves the database as it was). Stop the application first.
set -euo pipefail

file="${1:?usage: restore.sh /backups/vsuet-YYYYMMDDTHHMMSSZ.dump}"
pg_restore --list "$file" > /dev/null   # refuse an unreadable file before touching anything

echo "Restoring $file into database '$PGDATABASE' on '$PGHOST': all current data is replaced."
pg_restore --clean --if-exists --no-owner --no-privileges --single-transaction --dbname "$PGDATABASE" "$file"
echo "Restore finished."
