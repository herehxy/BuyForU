package com.buyforu.commerce.port;

import com.buyforu.commerce.port.model.CommerceModels.*;

/**
 * Commerce 的应用端口，也是 Agent 能接触到的全部交易能力。
 *
 * <p>接口刻意不包含 MCP 类型：MCP 只是传输适配器，未来改用 HTTP、RPC 或进程内调用时，
 * Agent 编排和 Commerce 领域模型无需变化。</p>
 */
public interface CommerceGateway {
    java.util.List<DeliveryAddress> listAddresses(String userId);

    java.util.List<InventoryItem> listInventory();

    SearchResult searchProducts(SearchRequest request);

    Quote quote(QuoteRequest request);

    DeliveryAddress registerAddress(RegisterAddressCommand command, EffectContext effectContext);

    ConfirmableOrderSnapshot prepareConfirmableOrder(
            PrepareOrderRequest request,
            EffectContext effectContext
    );

    void releaseReservation(String reservationId, EffectContext effectContext);

    Order createOrder(CreateOrderCommand command, EffectContext effectContext);

    /**
     * 取消一笔尚未成交的订单，并归还其占用的库存。
     *
     * <p>语义约束：</p>
     * <ul>
     *   <li>只有 {@code PENDING_PAYMENT → CANCELLED} 这一条迁移合法；CANCELLED 是吸收态。</li>
     *   <li>重复取消是幂等的：返回同一订单事实，且<b>不会再次回补库存</b>。</li>
     *   <li>取消是订单聚合上的操作，与产生订单的 Run 无关；调用方必须把幂等键锚在 orderId 上，
     *       不能锚在 runId 上——否则同一订单经两个 Run 取消会派生出两个键，导致库存被回补两次。</li>
     * </ul>
     */
    Order cancelOrder(CancelOrderCommand command, EffectContext effectContext);

    /**
     * 按人工确认快照查询已创建订单。
     * 该方法是纯读操作，用于解析“Commerce 已下单、Agent 尚未保存”的结果未知窗口。
     */
    java.util.Optional<Order> findOrderBySnapshot(String userId, String snapshotId);

    /**
     * 列出某用户已创建的订单，按创建时间倒序，最多 limit 条。
     *
     * <p>订单生命周期状态（待付款/已发货等）只会由交易系统推进，Agent 保存的运行快照
     * 只是下单时刻的副本。因此订单页必须走这条读路径，而不是回放历史 Run。</p>
     */
    java.util.List<Order> listOrders(String userId, int limit);
}
