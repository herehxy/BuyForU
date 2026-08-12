CREATE UNIQUE INDEX IF NOT EXISTS ux_effect_record_idempotency_key
    ON commerce_schema.effect_record (idempotency_key);

INSERT INTO commerce_schema.product (product_id, name, brand, category, attributes) VALUES
    ('p-100', 'Aurora Air 16 轻薄本', 'Aurora', 'laptop', '{"memory":"16GB","storage":"1TB","weight":"1.3kg"}'),
    ('p-200', 'Pine Pro 16 商务本', 'Pine', 'laptop', '{"memory":"16GB","storage":"512GB","weight":"1.45kg"}'),
    ('p-300', 'Nova Max 32 性能本', 'Nova', 'laptop', '{"memory":"32GB","storage":"1TB","weight":"2.1kg"}')
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO commerce_schema.sku (sku_id, product_id, status, unit_price, price_version) VALUES
    ('sku-air-16', 'p-100', 'ACTIVE', 4999.00, 1),
    ('sku-pro-16', 'p-200', 'ACTIVE', 4599.00, 1),
    ('sku-max-32', 'p-300', 'ACTIVE', 6299.00, 1)
ON CONFLICT (sku_id) DO NOTHING;

INSERT INTO commerce_schema.inventory (sku_id, available_quantity, version) VALUES
    ('sku-air-16', 8, 1),
    ('sku-pro-16', 5, 1),
    ('sku-max-32', 3, 1)
ON CONFLICT (sku_id) DO NOTHING;
