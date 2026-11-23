-- Official lists (fork, V1120-1129 reserved for lists): admin-curated lists shown to every account, each viewer
-- tracking their own progress. A row here marks custom_lists(id) official; no row means an ordinary list.
-- Kept in its own table instead of a column on upstream's custom_lists. Official implies public: the app sets
-- custom_lists.is_public when marking and keeps it set while the row exists. Deleting the list deletes the row.
CREATE TABLE official_lists (
  list_id BIGINT PRIMARY KEY REFERENCES custom_lists(id) ON DELETE CASCADE,
  created_at_epoch_ms BIGINT NOT NULL
);
