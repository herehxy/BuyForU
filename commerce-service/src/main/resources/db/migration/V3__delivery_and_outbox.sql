CREATE TABLE IF NOT EXISTS commerce_schema.delivery_zone (
    zone_code VARCHAR(32) PRIMARY KEY,
    delivery_days INTEGER NOT NULL CHECK (delivery_days > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS commerce_schema.customer_address (
    address_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    zone_code VARCHAR(32) NOT NULL REFERENCES commerce_schema.delivery_zone(zone_code),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO commerce_schema.delivery_zone (zone_code, delivery_days) VALUES
    ('CN-EAST', 1), ('CN-CENTRAL', 2), ('CN-WEST', 3)
ON CONFLICT (zone_code) DO NOTHING;

CREATE INDEX IF NOT EXISTS ix_customer_address_owner
    ON commerce_schema.customer_address(user_id, address_id) WHERE active;

CREATE INDEX IF NOT EXISTS ix_outbox_pending
    ON commerce_schema.outbox_event(status, created_at) WHERE status = 'PENDING';
