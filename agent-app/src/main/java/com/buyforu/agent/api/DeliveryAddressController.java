package com.buyforu.agent.api;

import com.buyforu.agent.application.DeliveryAddressService;
import com.buyforu.commerce.port.model.CommerceModels.DeliveryAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

/** 当前登录用户的可履约地址接口；当前领域模型以配送区域代表地址。 */
@RestController
@RequestMapping("/api/v1/addresses")
public class DeliveryAddressController {
    private final DeliveryAddressService service;

    public DeliveryAddressController(DeliveryAddressService service) {
        this.service = service;
    }

    @PostMapping
    DeliveryAddress register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterRequest request) {
        return service.register(AuthenticatedUser.id(jwt), request.zoneCode(), request.idempotencyKey());
    }

    @GetMapping
    java.util.List<DeliveryAddress> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(AuthenticatedUser.id(jwt));
    }

    public record RegisterRequest(@NotBlank @Size(max = 32) String zoneCode,
                                  @NotBlank @Size(max = 128) String idempotencyKey) {
    }
}
