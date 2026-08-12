ALTER TABLE commerce_schema.customer_address
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS ix_customer_address_recent
    ON commerce_schema.customer_address(user_id, created_at DESC)
    WHERE active;
