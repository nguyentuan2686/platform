-- Per-client cap on magic-link mail (MagicLinkPolicy.CLIENT_DAILY_MAX): who asked, as a keyed HMAC-SHA256 hex of the client IPv4
-- address or IPv6 /64 (dev.platform.identity.domain.ClientKey), never the address itself. NULL when web sent no client.
ALTER TABLE magic_link ADD COLUMN client_key text NULL;
CREATE INDEX magic_link_client_created_idx ON magic_link (client_key, created_at) WHERE client_key IS NOT NULL;
