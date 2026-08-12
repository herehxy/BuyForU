CREATE TABLE IF NOT EXISTS commerce_schema.promotion_rule (
    promotion_code VARCHAR(64) PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    minimum_spend NUMERIC(18, 2) NOT NULL CHECK (minimum_spend >= 0),
    discount_amount NUMERIC(18, 2) NOT NULL CHECK (discount_amount >= 0),
    priority INTEGER NOT NULL DEFAULT 0,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (ends_at > starts_at),
    CHECK (discount_amount <= minimum_spend)
);

CREATE TABLE IF NOT EXISTS commerce_schema.shipping_rule (
    rule_code VARCHAR(64) PRIMARY KEY,
    free_shipping_threshold NUMERIC(18, 2) NOT NULL CHECK (free_shipping_threshold >= 0),
    standard_fee NUMERIC(18, 2) NOT NULL CHECK (standard_fee >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO commerce_schema.promotion_rule
    (promotion_code, description, minimum_spend, discount_amount, priority, starts_at, ends_at, active)
VALUES
    ('FULL_5000_200', '满 5000 减 200', 5000.00, 200.00, 100,
     TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2099-12-31 23:59:59+00', TRUE)
ON CONFLICT (promotion_code) DO NOTHING;

INSERT INTO commerce_schema.shipping_rule
    (rule_code, free_shipping_threshold, standard_fee, active)
VALUES ('STANDARD', 99.00, 10.00, TRUE)
ON CONFLICT (rule_code) DO NOTHING;

WITH ranked AS (
    SELECT address_id,
           row_number() OVER (PARTITION BY user_id, zone_code ORDER BY created_at DESC, address_id DESC) AS rn
    FROM commerce_schema.customer_address
    WHERE active
)
UPDATE commerce_schema.customer_address address
SET active = FALSE
FROM ranked
WHERE address.address_id = ranked.address_id AND ranked.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_address_active_zone
    ON commerce_schema.customer_address(user_id, zone_code) WHERE active;

CREATE INDEX IF NOT EXISTS ix_promotion_rule_active_window
    ON commerce_schema.promotion_rule(active, starts_at, ends_at, priority DESC);
