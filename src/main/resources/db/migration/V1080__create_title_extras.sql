-- Fork: cached TMDB extras (trailer, runtime, vote count, IMDb id) and external scores from MDBList,
-- one row per title. The two halves refresh on their own schedules (see org.ratelog.extras.RefreshPolicy).
CREATE TABLE title_extras (
  id BIGSERIAL PRIMARY KEY,
  media_type VARCHAR(10) NOT NULL,
  tmdb_id INTEGER NOT NULL,
  imdb_id VARCHAR(20),

  -- From TMDB (refreshed weekly)
  title VARCHAR(500),
  -- Movie release date, or a show's last air date; drives the scores refresh policy.
  latest_release_date DATE,
  tmdb_vote_average DOUBLE PRECISION,
  tmdb_vote_count INTEGER,
  -- Movie runtime, or a show's episode runtime.
  runtime_minutes INTEGER,
  number_of_seasons INTEGER,
  trailer_youtube_key VARCHAR(64),
  tmdb_fetched_at_epoch_ms BIGINT,
  tmdb_next_refresh_at_epoch_ms BIGINT,

  -- From MDBList
  imdb_rating DOUBLE PRECISION,
  imdb_votes BIGINT,
  rt_critics INTEGER,
  rt_audience INTEGER,
  metacritic INTEGER,
  letterboxd DOUBLE PRECISION,
  -- true when MDBList had no scores for the title on the last fetch
  scores_no_data BOOLEAN NOT NULL DEFAULT false,
  scores_fetched_at_epoch_ms BIGINT,
  scores_next_refresh_at_epoch_ms BIGINT,

  UNIQUE (media_type, tmdb_id)
);
