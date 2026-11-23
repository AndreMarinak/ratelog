-- List options (fork, V1120-1129 reserved for lists). One row per list that changed a setting; no row means the
-- defaults. Kept in its own table instead of columns on upstream's custom_lists. Deleting the list deletes the row.
-- numbered: the list page shows each title's rank (a ranked list such as "IMDb Top 250"); false = a plain collection
-- shown without numbers. The order (custom_list_items.position) is kept and editable either way.
CREATE TABLE custom_list_options (
  list_id BIGINT PRIMARY KEY REFERENCES custom_lists(id) ON DELETE CASCADE,
  numbered BOOLEAN NOT NULL
);
