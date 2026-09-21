package com.buyforu.agent.eval;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 直连本机 Ollama {@code /api/embed} 的最小 EmbeddingModel 实现，只服务于评测。
 *
 * 为什么不直接用 {@code OllamaEmbeddingModel}：它的 Builder 要求 ObservationRegistry、
 * ModelManagementOptions 等必填项，而这些与"评测能不能跑"无关。评测真正需要的只有一件事——
 * **必须是真实嵌入**。阈值是否可达是个语义性质，用假的嵌入模型（哈希、词袋）根本守不住它。
 *
 * 刻意只实现知识库实际调用的两个方法（{@code embed(List)} 与 {@code embed(String)}），
 * 其余入口直接抛异常——被测代码若改走别的入口，评测会立刻失败而不是悄悄用错模型。
 */
final class DirectOllamaEmbeddingModel implements EmbeddingModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String baseUrl;
    private final String model;

    DirectOllamaEmbeddingModel(String baseUrl, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        // JDK HttpClient 默认走系统 ProxySelector；执行环境里注入的 HTTP_PROXY 会让访问本机
        // 服务拿到假的 502，这里显式禁用代理。
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(new NoProxySelector())
                .build();
    }

    /** 探活：Ollama 没起时应当跳过评测，而不是把评测判成失败。 */
    boolean reachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        String payload = JSON.writeValueAsString(new EmbedRequest(model, texts));
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new IllegalStateException("Ollama 嵌入调用失败，确认 " + baseUrl + " 可访问", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama 嵌入调用被中断", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama 嵌入返回 " + response.statusCode() + "：" + response.body());
        }
        JsonNode embeddings = JSON.readTree(response.body()).get("embeddings");
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != texts.size()) {
            throw new IllegalStateException("Ollama 嵌入返回条数与请求不一致");
        }
        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (JsonNode vector : embeddings) {
            float[] values = new float[vector.size()];
            for (int index = 0; index < vector.size(); index++) {
                values[index] = (float) vector.get(index).asDouble();
            }
            vectors.add(values);
        }
        return vectors;
    }

    @Override
    public float[] embed(String text) {
        return embed(List.of(text)).getFirst();
    }

    /** 知识库走不到这个入口；走到就说明被测代码改了路径，评测应当显式失败。 */
    @Override
    public float[] embed(Document document) {
        throw new UnsupportedOperationException("评测替身不支持 Document 入口，请改用 embed(String)");
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        throw new UnsupportedOperationException("评测替身不支持 EmbeddingRequest 入口");
    }

    @Override
    public int dimensions() {
        return embed("dimension probe").length;
    }

    private record EmbedRequest(String model, List<String> input) { }

    private static final class NoProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // 没有真实代理，无需处理
        }
    }
}
