-- Per-user watch status (watchlist): Want to watch, Watching, Watched, Dropped. No row means "no status".
-- Separate from movie_follows / tv_follows, which only drive premiere tracking.
-- media_id points at movies.id or tv.id depending on media_type (same convention as custom_list_items).
CREATE TABLE media_statuses (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  media_type VARCHAR(10) NOT NULL CHECK (media_type IN ('movie', 'tvshow')),
  media_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('WANT_TO_WATCH', 'WATCHING', 'WATCHED', 'DROPPED')),
  updated_at_epoch_ms BIGINT NOT NULL,
  UNIQUE (user_id, media_type, media_id)
);

CREATE INDEX idx_media_statuses_user_status ON media_statuses(user_id, status, updated_at_epoch_ms DESC);
