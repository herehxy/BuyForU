package com.buyforu.agent.application;

import java.util.List;

/** 规划阶段使用的知识召回端口；实现可替换，但命中必须保留来源和版本。 */
public interface KnowledgeRetriever {
    List<KnowledgeHit> retrieve(String query, int limit, double minimumScore);

    /** 一条可审计的知识证据，score 是向量相似度而不是业务可信等级。 */
    record KnowledgeHit(String documentId, String title, String sourceUri, String version,
                        String chunkId, String content, double score) {
    }
}
