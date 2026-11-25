-- Runs once on first Postgres start (docker-entrypoint-initdb.d). Dev-only credentials.
CREATE USER orders WITH PASSWORD 'orders';
CREATE DATABASE orders OWNER orders;
CREATE USER notify WITH PASSWORD 'notify';
CREATE DATABASE notify OWNER notify;
CREATE USER reading WITH PASSWORD 'reading';
CREATE DATABASE reading_tuvi OWNER reading;
CREATE USER identity WITH PASSWORD 'identity';
CREATE DATABASE identity OWNER identity;
CREATE USER payment WITH PASSWORD 'payment';
CREATE DATABASE payment OWNER payment;
