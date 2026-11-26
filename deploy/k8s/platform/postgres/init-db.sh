#!/bin/sh
# Runs once on first start (docker-entrypoint-initdb.d). Mirrors deploy/local/init-db.sql,
# but passwords come from the postgres-secrets env vars instead of being hard-coded.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v orders_pw="$ORDERS_DB_PASSWORD" -v notify_pw="$NOTIFY_DB_PASSWORD" \
  -v reading_pw="$READING_DB_PASSWORD" -v identity_pw="$IDENTITY_DB_PASSWORD" \
  -v payment_pw="$PAYMENT_DB_PASSWORD" <<'SQL'
CREATE USER orders WITH PASSWORD :'orders_pw';
CREATE DATABASE orders OWNER orders;
CREATE USER notify WITH PASSWORD :'notify_pw';
CREATE DATABASE notify OWNER notify;
CREATE USER reading WITH PASSWORD :'reading_pw';
CREATE DATABASE reading_tuvi OWNER reading;
CREATE USER identity WITH PASSWORD :'identity_pw';
CREATE DATABASE identity OWNER identity;
CREATE USER payment WITH PASSWORD :'payment_pw';
CREATE DATABASE payment OWNER payment;
SQL
