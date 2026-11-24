-- Fork (2026-11-24, rewatch log): one row per time you watched a title again. The first watch is the existing seen
-- state (status Watched, or rated), so a title's watch count is 1 + its rows here. Rows of a title that isn't seen any
-- more are kept but not counted (seeing it again brings them back). See docs/decisions/watchlist-and-seen.md.
CREATE TABLE rewatches (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  media_type VARCHAR(10) NOT NULL CHECK (media_type IN ('movie', 'tvshow')),
  -- Local id (movies.id / tv.id), like media_statuses and watchlist_entries.
  media_id BIGINT NOT NULL,
  watched_at_epoch_ms BIGINT NOT NULL
);

CREATE INDEX idx_rewatches_title ON rewatches(user_id, media_type, media_id);
CREATE INDEX idx_rewatches_user_time ON rewatches(user_id, watched_at_epoch_ms DESC);
