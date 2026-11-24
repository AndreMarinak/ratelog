-- Fork (2026-11-23): a TV show can be rated as a whole, without rating seasons.
-- tv_ratings.score stays the show's overall score that every reader uses: the average of the rated seasons when at
-- least one season is rated, else show_score. show_score is kept while seasons are rated (it is just not used), so
-- deleting every season rating brings it back. A row with neither seasons nor show_score is deleted.
ALTER TABLE tv_ratings ADD COLUMN IF NOT EXISTS show_score DOUBLE PRECISION;
ALTER TABLE tv_ratings ADD COLUMN IF NOT EXISTS show_rated_at_epoch_ms BIGINT;

ALTER TABLE tv_ratings ADD CONSTRAINT tv_ratings_show_score_range CHECK (show_score IS NULL OR (show_score >= 0 AND show_score <= 10));
