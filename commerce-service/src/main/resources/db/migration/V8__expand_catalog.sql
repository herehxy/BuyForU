-- 扩充本地演示目录。价格、库存仍由 Commerce 表权威持有，不从网页抓取。

UPDATE commerce_schema.product
SET attributes = attributes || '{"type":"轻薄本"}'::jsonb
WHERE product_id = 'p-100';
UPDATE commerce_schema.product
SET attributes = attributes || '{"type":"商务本"}'::jsonb
WHERE product_id = 'p-200';
UPDATE commerce_schema.product
SET attributes = attributes || '{"type":"性能本"}'::jsonb
WHERE product_id = 'p-300';

INSERT INTO commerce_schema.product (product_id, name, brand, category, attributes) VALUES
    ('p-110', 'Aurora Air 14 轻薄本', 'Aurora', 'laptop', '{"memory":"16GB","storage":"512GB","weight":"1.15kg","type":"轻薄本"}'),
    ('p-120', 'Nova Forge 16 游戏本', 'Nova', 'laptop', '{"memory":"16GB","storage":"1TB","weight":"2.3kg","type":"游戏本"}'),
    ('p-130', 'Cedar Leaf 13 轻薄本', 'Cedar', 'laptop', '{"memory":"16GB","storage":"512GB","weight":"1.1kg","type":"轻薄本"}'),
    ('p-140', 'Pine Book 15 办公本', 'Pine', 'laptop', '{"memory":"8GB","storage":"512GB","weight":"1.6kg","type":"商务本"}'),
    ('p-150', 'Aurora Pro 16 创作本', 'Aurora', 'laptop', '{"memory":"32GB","storage":"2TB","weight":"1.8kg","type":"创作本"}'),
    ('p-400', 'Lumen Phone 12', 'Lumen', 'phone', '{"memory":"8GB","storage":"256GB","screen":"6.1","type":"手机"}'),
    ('p-410', 'Lumen Phone 12 Pro', 'Lumen', 'phone', '{"memory":"12GB","storage":"256GB","screen":"6.3","type":"手机"}'),
    ('p-420', 'Cedar Pulse 5', 'Cedar', 'phone', '{"memory":"8GB","storage":"128GB","screen":"6.5","type":"手机"}'),
    ('p-430', 'Nova Pixel 8', 'Nova', 'phone', '{"memory":"12GB","storage":"512GB","screen":"6.7","type":"手机"}'),
    ('p-500', 'Hush Air 耳机', 'Hush', 'headphone', '{"anc":"yes","battery":"30h","type":"耳机"}'),
    ('p-510', 'Hush Studio 耳机', 'Hush', 'headphone', '{"anc":"yes","battery":"40h","type":"耳机"}'),
    ('p-520', 'Cedar Buds 2', 'Cedar', 'headphone', '{"anc":"no","battery":"24h","type":"耳机"}'),
    ('p-600', 'Slate Tab 11', 'Slate', 'tablet', '{"memory":"8GB","storage":"128GB","screen":"11","type":"平板"}'),
    ('p-610', 'Slate Tab 12 Pro', 'Slate', 'tablet', '{"memory":"12GB","storage":"256GB","screen":"12.4","type":"平板"}'),
    ('p-700', 'Click Slim 键盘', 'Click', 'keyboard', '{"layout":"87","wireless":"yes","type":"键盘"}'),
    ('p-710', 'Click Desk 鼠标', 'Click', 'mouse', '{"dpi":"16000","wireless":"yes","type":"鼠标"}'),
    ('p-800', 'Vista 27 显示器', 'Vista', 'monitor', '{"size":"27","refresh":"144Hz","type":"显示器"}'),
    ('p-810', 'Vista 32 显示器', 'Vista', 'monitor', '{"size":"32","refresh":"165Hz","type":"显示器"}')
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO commerce_schema.sku (sku_id, product_id, status, unit_price, price_version) VALUES
    ('sku-air-14', 'p-110', 'ACTIVE', 3999.00, 1),
    ('sku-forge-16', 'p-120', 'ACTIVE', 7299.00, 1),
    ('sku-leaf-13', 'p-130', 'ACTIVE', 4699.00, 1),
    ('sku-book-15', 'p-140', 'ACTIVE', 3299.00, 1),
    ('sku-pro-create', 'p-150', 'ACTIVE', 8999.00, 1),
    ('sku-lumen-12', 'p-400', 'ACTIVE', 3999.00, 1),
    ('sku-lumen-12p', 'p-410', 'ACTIVE', 5299.00, 1),
    ('sku-pulse-5', 'p-420', 'ACTIVE', 2499.00, 1),
    ('sku-pixel-8', 'p-430', 'ACTIVE', 5999.00, 1),
    ('sku-hush-air', 'p-500', 'ACTIVE', 899.00, 1),
    ('sku-hush-studio', 'p-510', 'ACTIVE', 1999.00, 1),
    ('sku-buds-2', 'p-520', 'ACTIVE', 299.00, 1),
    ('sku-tab-11', 'p-600', 'ACTIVE', 3299.00, 1),
    ('sku-tab-12p', 'p-610', 'ACTIVE', 4599.00, 1),
    ('sku-click-kbd', 'p-700', 'ACTIVE', 399.00, 1),
    ('sku-click-mouse', 'p-710', 'ACTIVE', 129.00, 1),
    ('sku-vista-27', 'p-800', 'ACTIVE', 1299.00, 1),
    ('sku-vista-32', 'p-810', 'ACTIVE', 2499.00, 1)
ON CONFLICT (sku_id) DO NOTHING;

INSERT INTO commerce_schema.inventory (sku_id, available_quantity, version) VALUES
    ('sku-air-14', 12, 1),
    ('sku-forge-16', 4, 1),
    ('sku-leaf-13', 7, 1),
    ('sku-book-15', 9, 1),
    ('sku-pro-create', 2, 1),
    ('sku-lumen-12', 15, 1),
    ('sku-lumen-12p', 6, 1),
    ('sku-pulse-5', 20, 1),
    ('sku-pixel-8', 5, 1),
    ('sku-hush-air', 18, 1),
    ('sku-hush-studio', 8, 1),
    ('sku-buds-2', 30, 1),
    ('sku-tab-11', 10, 1),
    ('sku-tab-12p', 6, 1),
    ('sku-click-kbd', 25, 1),
    ('sku-click-mouse', 40, 1),
    ('sku-vista-27', 11, 1),
    ('sku-vista-32', 4, 1)
ON CONFLICT (sku_id) DO NOTHING;
