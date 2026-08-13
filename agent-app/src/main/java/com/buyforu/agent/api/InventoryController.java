package com.buyforu.agent.api;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.InventoryItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 登录用户可读的目录库存。数字以 Commerce 为准，页面只展示、不能改。 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final CommerceGateway commerce;

    public InventoryController(CommerceGateway commerce) {
        this.commerce = commerce;
    }

    @GetMapping
    List<InventoryItem> list(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser.id(jwt);
        return commerce.listInventory();
    }
}
