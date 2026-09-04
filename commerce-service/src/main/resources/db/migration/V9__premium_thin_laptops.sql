-- 「7000 以上轻薄本」原先目录里几乎没有对应 SKU，搜索下限会空。补两台旗舰轻薄本。

INSERT INTO commerce_schema.product (product_id, name, brand, category, attributes) VALUES
    ('p-160', 'Aurora Air 16 Ultra 轻薄本', 'Aurora', 'laptop',
     '{"memory":"16GB","storage":"1TB","weight":"1.25kg","type":"轻薄本"}'),
    ('p-170', 'Cedar Leaf 14 Ultra 轻薄本', 'Cedar', 'laptop',
     '{"memory":"16GB","storage":"1TB","weight":"1.05kg","type":"轻薄本"}')
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO commerce_schema.sku (sku_id, product_id, status, unit_price, price_version) VALUES
    ('sku-air-ultra', 'p-160', 'ACTIVE', 7599.00, 1),
    ('sku-leaf-ultra', 'p-170', 'ACTIVE', 8299.00, 1)
ON CONFLICT (sku_id) DO NOTHING;

INSERT INTO commerce_schema.inventory (sku_id, available_quantity, version) VALUES
    ('sku-air-ultra', 4, 1),
    ('sku-leaf-ultra', 3, 1)
ON CONFLICT (sku_id) DO NOTHING;
