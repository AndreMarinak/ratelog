-- Saved lists (fork, V1142): a user "saves" an official list, like saving a playlist (user request 2026-11-24). A saved
-- list comes first in the Official lists sections and shows under your own lists; the title page and the add-to-list
-- sheet name it. One row per (user, list); deleting the user or the list deletes the row. Only official lists can be
-- saved for now (the rule lives in the app, SavedListPolicy), so ordinary public lists could be allowed later without
-- changing this table. A saved list that stops being official or public keeps its row but is shown nowhere until it is
-- visible again.
CREATE TABLE saved_lists (
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  list_id BIGINT NOT NULL REFERENCES custom_lists(id) ON DELETE CASCADE,
  saved_at_epoch_ms BIGINT NOT NULL,
  PRIMARY KEY (user_id, list_id)
);

CREATE INDEX idx_saved_lists_list ON saved_lists(list_id);
