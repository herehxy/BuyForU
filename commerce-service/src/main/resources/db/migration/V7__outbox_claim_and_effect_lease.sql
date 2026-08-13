-- Outbox 先短事务认领，再在事务外发 HTTP。CLAIMED 超时后打回 PENDING。
ALTER TABLE commerce_schema.outbox_event
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(160);

CREATE INDEX IF NOT EXISTS ix_outbox_claimed_stale
    ON commerce_schema.outbox_event(claimed_at)
    WHERE status = 'CLAIMED';

ALTER TABLE commerce_schema.effect_record
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
