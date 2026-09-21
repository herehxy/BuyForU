package com.buyforu.agent.api;

import com.buyforu.agent.application.OrderCancellationService;
import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.Order;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 已提交订单的视图与撤销入口。
 *
 * <p>订单状态由交易系统推进，Agent 保存的 Run 快照只是下单时刻的副本。
 * 因此本接口每次都回到 Commerce 读取当前状态，不做任何本地缓存或重算；
 * userId 只取自已校验 JWT，客户端无法查询或取消别人的订单。</p>
 *
 * <p>取消是本控制器唯一的写操作，且与 Run 取消是两个不同聚落上的独立动作：
 * 撤销订单不会回写历史 Run，Run 侧也不会把订单伪装成已取消。</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final CommerceGateway commerce;
    private final OrderCancellationService cancellations;

    public OrderController(CommerceGateway commerce, OrderCancellationService cancellations) {
        this.commerce = commerce;
        this.cancellations = cancellations;
    }

    @GetMapping
    List<Order> list(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(defaultValue = "20") int limit) {
        return commerce.listOrders(AuthenticatedUser.id(jwt), limit);
    }

    /**
     * 撤销一笔尚未成交的订单。
     *
     * <p>刻意不接收客户端幂等键：幂等身份完全由 orderId 确定性派生，重复提交必然重放同一结果。
     * 把幂等性交给服务端推导，比要求每个客户端都正确复用同一个键更可靠。</p>
     */
    @PostMapping("/{orderId}/cancellations")
    Order cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        return cancellations.cancel(AuthenticatedUser.id(jwt), orderId);
    }
}
