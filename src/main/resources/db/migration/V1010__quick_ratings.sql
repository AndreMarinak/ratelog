-- Quick rating (fork): a movie or season rating can be a single overall score instead of the five categories.
-- Detailed rating: all five categories set, score = their average (as before).
-- Quick rating:    all five categories NULL, score set directly.
ALTER TABLE movie_ratings
    ALTER COLUMN directing DROP NOT NULL,
    ALTER COLUMN cinematography DROP NOT NULL,
    ALTER COLUMN acting DROP NOT NULL,
    ALTER COLUMN soundtrack DROP NOT NULL,
    ALTER COLUMN screenplay DROP NOT NULL;

ALTER TABLE season_ratings
    ALTER COLUMN directing DROP NOT NULL,
    ALTER COLUMN cinematography DROP NOT NULL,
    ALTER COLUMN acting DROP NOT NULL,
    ALTER COLUMN soundtrack DROP NOT NULL,
    ALTER COLUMN screenplay DROP NOT NULL;

-- All five categories or none, and a quick rating must have its score. Existing rows are all detailed.
ALTER TABLE movie_ratings ADD CONSTRAINT movie_ratings_quick_or_detailed CHECK (
    (directing IS NOT NULL AND cinematography IS NOT NULL AND acting IS NOT NULL
        AND soundtrack IS NOT NULL AND screenplay IS NOT NULL)
    OR (directing IS NULL AND cinematography IS NULL AND acting IS NULL
        AND soundtrack IS NULL AND screenplay IS NULL AND score IS NOT NULL)
);

ALTER TABLE season_ratings ADD CONSTRAINT season_ratings_quick_or_detailed CHECK (
    (directing IS NOT NULL AND cinematography IS NOT NULL AND acting IS NOT NULL
        AND soundtrack IS NOT NULL AND screenplay IS NOT NULL)
    OR (directing IS NULL AND cinematography IS NULL AND acting IS NULL
        AND soundtrack IS NULL AND screenplay IS NULL AND score IS NOT NULL)
);
