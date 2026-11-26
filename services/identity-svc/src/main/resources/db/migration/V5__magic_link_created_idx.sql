-- The global daily cap on magic-link mail counts links over all addresses in a rolling day.
CREATE INDEX magic_link_created_idx ON magic_link (created_at);
