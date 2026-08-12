package com.buyforu.agent.concurrency;

import com.buyforu.agent.domain.PlanSpec.ShoppingConstraints;

/** 所有命令的持久化输入；未使用字段保持 null，禁止存入凭据和完整地址。 */
public record CommandPayload(String conversationId, String message, String skuId, String snapshotId,
                             String summaryHash, ShoppingConstraints constraints) { }
