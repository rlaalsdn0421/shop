-- Both columns are nullable with no default: existing rows (seed + production) stay NULL = "no discount, no tags",
-- so this is safe on a populated table and needs no backfill. Adding a nullable column without a default is
-- metadata-only; the CHECK on original_price is validated with one scan of the table under an ACCESS EXCLUSIVE
-- lock (trivial at this table size, worth knowing if products ever gets large).

-- Time-independent rule, so a CHECK is fine. NULL passes the CHECK (original_price IS NULL branch).
ALTER TABLE products ADD COLUMN original_price INTEGER
    CONSTRAINT products_original_price_gt_price CHECK (original_price IS NULL OR original_price > price);

-- Normalized tags (NFC, no '#', no whitespace) joined by one space, max 3 tags x 20 chars + 2 spaces = 62 chars.
-- shortcut: display-only today; if tag search/click-through is added, move to a product_tags table.
ALTER TABLE products ADD COLUMN hashtags VARCHAR(100);
