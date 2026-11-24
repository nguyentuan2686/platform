-- Runs once on first Postgres start (docker-entrypoint-initdb.d). Dev-only credentials.
CREATE USER orders WITH PASSWORD 'orders';
CREATE DATABASE orders OWNER orders;
CREATE USER notify WITH PASSWORD 'notify';
CREATE DATABASE notify OWNER notify;
