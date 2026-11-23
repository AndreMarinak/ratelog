-- Custom tags (fork, reserved range V1030-V1039). Tags are private to each user.
CREATE TABLE tags (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name VARCHAR(40) NOT NULL,
  color VARCHAR(20),
  created_at_epoch_ms BIGINT NOT NULL
);

-- Names are unique per user ignoring case ("Comfort watch" = "comfort watch").
CREATE UNIQUE INDEX ux_tags_user_lower_name ON tags(user_id, LOWER(name));

-- media_id points at movies.id or tv.id depending on media_type (same scheme as custom_list_items).
CREATE TABLE tag_assignments (
  id BIGSERIAL PRIMARY KEY,
  tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  media_type VARCHAR(10) NOT NULL CHECK (media_type IN ('movie', 'tvshow')),
  media_id BIGINT NOT NULL,
  created_at_epoch_ms BIGINT NOT NULL,
  UNIQUE(tag_id, media_type, media_id)
);

CREATE INDEX idx_tag_assignments_media ON tag_assignments(media_type, media_id);
