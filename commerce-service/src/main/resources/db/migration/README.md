# Commerce 数据库迁移说明

Flyway 会保存每个已执行版本的 checksum。即使只给历史 SQL 添加注释，也会导致已有数据库校验失败，所以逐文件说明放在本 README；所有未来迁移在首次创建时应包含文件头和关键事务/索引说明。

| 文件 | 作用 | 关键设计 |
| --- | --- | --- |
| `V1__commerce_core.sql` | 商品、SKU、库存、预占、快照、订单、effect ledger 和 outbox 基础表 | 订单来源快照和预占均唯一；库存数量有非负约束 |
| `V2__seed_and_idempotency.sql` | effect 网络幂等键唯一索引及本地商品种子 | 种子使用 `ON CONFLICT DO NOTHING`，可重复执行但不覆盖业务数据 |
| `V3__delivery_and_outbox.sql` | 配送区域、用户地址以及待投递 Outbox 索引 | 地址属于用户；Outbox 只扫描 `PENDING` 状态 |
| `V4__durable_versions_and_expiry.sql` | 报价/快照数据库序列、预占过期索引和 Outbox 重试字段 | 版本不依赖单机内存；事件支持退避、错误和发布时间 |
| `V5__address_created_at.sql` | 地址创建时间及最近地址索引 | 用于刷新页面时恢复用户最近使用的地址 |
| `V6__pricing_rules_and_address_identity.sql` | 促销、运费规则和活动地址唯一语义 | 金额规则进入 Commerce 数据库；同一用户和区域只保留一个活动地址 |

修改交易表只能增加新版本迁移，严禁回改已发布脚本或手工修改生产 Schema。
