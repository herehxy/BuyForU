CREATE SEQUENCE IF NOT EXISTS commerce_schema.quote_version_seq START WITH 1;
CREATE SEQUENCE IF NOT EXISTS commerce_schema.snapshot_version_seq START WITH 1;

CREATE INDEX IF NOT EXISTS ix_reservation_expiry
    ON commerce_schema.inventory_reservation(expires_at)
    WHERE status = 'ACTIVE';

ALTER TABLE commerce_schema.outbox_event
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;

DROP INDEX IF EXISTS commerce_schema.ix_outbox_pending;
CREATE INDEX IF NOT EXISTS ix_outbox_dispatchable
    ON commerce_schema.outbox_event(next_attempt_at, created_at)
    WHERE status = 'PENDING';
