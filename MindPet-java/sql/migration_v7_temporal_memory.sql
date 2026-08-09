-- Store event time separately from memory creation time.
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_date DATE;
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_at TIMESTAMP;
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_timezone VARCHAR(64);
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_precision VARCHAR(16);
