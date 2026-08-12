CREATE SCHEMA IF NOT EXISTS commerce_schema;

CREATE TABLE IF NOT EXISTS commerce_schema.product (
    product_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    brand VARCHAR(128) NOT NULL,
    category VARCHAR(128) NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS commerce_schema.sku (
    sku_id VARCHAR(64) PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL REFERENCES commerce_schema.product(product_id),
    status VARCHAR(32) NOT NULL,
    unit_price NUMERIC(18, 2) NOT NULL,
    price_version BIGINT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS commerce_schema.inventory (
    sku_id VARCHAR(64) PRIMARY KEY REFERENCES commerce_schema.sku(sku_id),
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    version BIGINT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS commerce_schema.inventory_reservation (
    reservation_id VARCHAR(64) PRIMARY KEY,
    sku_id VARCHAR(64) NOT NULL REFERENCES commerce_schema.sku(sku_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS commerce_schema.confirmable_snapshot (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL REFERENCES commerce_schema.inventory_reservation(reservation_id),
    summary_hash VARCHAR(64) NOT NULL,
    snapshot JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS commerce_schema.orders (
    order_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    source_snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    reservation_id VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    order_payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS commerce_schema.effect_record (
    effect_id VARCHAR(128) PRIMARY KEY,
    operation_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64),
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS commerce_schema.outbox_event (
    event_id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
