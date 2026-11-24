-- Fork (2026-11-24): director / writers / cast / age rating / tagline and friends for title pages, as one compact JSON
-- value (org.ratelog.extras.TitleCredits), fetched with the same TMDB request as the other extras.
ALTER TABLE title_extras ADD COLUMN IF NOT EXISTS credits_json TEXT;

-- Rows cached before credits existed: due now, so the next view of each title fetches its credits
-- (GetTitleInfoHandler waits for that fetch like a first view, at most ratelog.extras.sync-timeout-ms).
UPDATE title_extras SET tmdb_next_refresh_at_epoch_ms = 0 WHERE tmdb_fetched_at_epoch_ms IS NOT NULL;
