-- 订单取消：第二条写路径。
--
-- 本迁移只做三件事，都不改变既有数据语义：
--   1) 把订单状态机写进数据库约束（此前 status 是裸 VARCHAR(32)，状态机只存在于应用层约定里）；
--   2) 记录取消时间，供审计与"取消耗时"观测；
--   3) 为按状态排查/对账建立索引。
--
-- 注意：CHECK 只枚举"合法状态名"，不表达"合法迁移"。迁移合法性由应用层的条件更新
-- （UPDATE ... WHERE status = <期望源状态>）保证，命中行数决定副作用是否发生。

ALTER TABLE commerce_schema.orders
    ADD CONSTRAINT orders_status_known CHECK (
        status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLING', 'SHIPPED', 'COMPLETED',
                   'CANCELLED', 'REFUND_PENDING', 'REFUNDED')
    );

-- 只有 PENDING_PAYMENT 与 CANCELLED 是可达到的状态；其余五个由 CHECK 放行但当前无人写入，
-- 属于为支付/履约/退款预留的接线位。保留它们是为了让"未接线"是显式的，而不是靠删枚举隐藏。

ALTER TABLE commerce_schema.orders ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_orders_status ON commerce_schema.orders (status);
