#!/bin/sh
set -eu

# setup-schema preserves versions newer than 0.0; update-schema applies pending migrations.
for database in temporal temporal_visibility; do
  schema_directory=temporal
  if [ "$database" = temporal_visibility ]; then
    schema_directory=visibility
  fi
  temporal-sql-tool --plugin postgres12 --ep postgresql -u temporal -p 5432 \
    --db "$database" setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep postgresql -u temporal -p 5432 \
    --db "$database" update-schema \
    -d "/etc/temporal/schema/postgresql/v12/$schema_directory/versioned"
done
