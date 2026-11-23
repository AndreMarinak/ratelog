-- Fork migrations use V1000+ so they never collide with upstream version numbers.
-- spring.flyway.out-of-order is enabled so later upstream migrations (V22, V23, ...) still apply.
CREATE TABLE api_tokens (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name VARCHAR(100) NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  token_prefix VARCHAR(16) NOT NULL,
  created_at_epoch_ms BIGINT NOT NULL,
  last_used_at_epoch_ms BIGINT
);

CREATE INDEX idx_api_tokens_user ON api_tokens(user_id);
