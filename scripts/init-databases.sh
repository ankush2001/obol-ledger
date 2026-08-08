#!/bin/sh
# Runs once, on first initialisation of the Postgres data volume.
# obol-ledger and accord-recon are separate services with separate schemas;
# in development they share one Postgres instance to keep the local
# footprint small. In production they get their own databases.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	CREATE DATABASE accord;
	GRANT ALL PRIVILEGES ON DATABASE accord TO $POSTGRES_USER;
EOSQL
