-- Fork (2026-11-24, roadmap item 32): the watchlist is its own table, independent of the watch status.
-- "On my watchlist" used to be the status WANT_TO_WATCH, so a title couldn't be seen (Watched) and on the watchlist at once.
-- Now: media_statuses holds progress only (Watching / Watched / Dropped) and watchlist_entries is the one source of truth
-- for "on the watchlist". A title can be in both (a rewatch). See docs/design-decisions.md.
CREATE TABLE watchlist_entries (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  media_type VARCHAR(10) NOT NULL CHECK (media_type IN ('movie', 'tvshow')),
  media_id BIGINT NOT NULL,
  added_at_epoch_ms BIGINT NOT NULL,
  -- Already seen when it was added: marking it seen or rating it again doesn't take it off (it was put there to rewatch).
  -- Not a "seen" flag: seen is always computed from the status and the ratings.
  rewatch BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (user_id, media_type, media_id)
);

CREATE INDEX idx_watchlist_entries_user ON watchlist_entries(user_id, added_at_epoch_ms DESC);

-- Every Want to watch moves over, keeping its date. A rated title on the watchlist was already seen through its rating.
INSERT INTO watchlist_entries (user_id, media_type, media_id, added_at_epoch_ms, rewatch)
SELECT s.user_id, s.media_type, s.media_id, s.updated_at_epoch_ms,
       CASE
         WHEN s.media_type = 'movie' THEN
           EXISTS (SELECT 1 FROM movie_ratings r WHERE r.user_id = s.user_id AND r.movie_id = s.media_id)
         ELSE
           EXISTS (SELECT 1 FROM season_ratings sr WHERE sr.user_id = s.user_id AND sr.tv_show_id = s.media_id)
           OR EXISTS (SELECT 1 FROM tv_ratings tr WHERE tr.user_id = s.user_id AND tr.tv_show_id = s.media_id
                       AND tr.show_score IS NOT NULL)
       END
FROM media_statuses s
WHERE s.status = 'WANT_TO_WATCH';

DELETE FROM media_statuses WHERE status = 'WANT_TO_WATCH';

-- WANT_TO_WATCH is never stored as a status again.
ALTER TABLE media_statuses DROP CONSTRAINT IF EXISTS media_statuses_status_check;
ALTER TABLE media_statuses ADD CONSTRAINT media_statuses_status_check CHECK (status IN ('WATCHING', 'WATCHED', 'DROPPED'));
