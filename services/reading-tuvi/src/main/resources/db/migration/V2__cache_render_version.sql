ALTER TABLE basic_reading_cache ADD COLUMN render_version int NOT NULL DEFAULT 1;
ALTER TABLE basic_reading_cache DROP CONSTRAINT basic_reading_cache_pkey;
ALTER TABLE basic_reading_cache ADD PRIMARY KEY (chart_hash, knowledge_version, render_version);
