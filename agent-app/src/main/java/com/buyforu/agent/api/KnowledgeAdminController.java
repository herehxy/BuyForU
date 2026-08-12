package com.buyforu.agent.api;

import com.buyforu.agent.infrastructure.knowledge.PgVectorKnowledgeStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

/** 受 knowledge-admin 角色保护的知识入库接口，不向普通 Web 用户开放。 */
@RestController
@RequestMapping("/internal/v1/knowledge")
public class KnowledgeAdminController {
    private final PgVectorKnowledgeStore store;

    public KnowledgeAdminController(PgVectorKnowledgeStore store) {
        this.store = store;
    }

    @PostMapping("/documents")
    public PgVectorKnowledgeStore.IndexedDocument index(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IndexDocumentRequest request) {
        return store.index(new PgVectorKnowledgeStore.IndexDocument(request.documentId(), request.title(),
                request.sourceUri(), request.version(), request.expectedVersion(), request.content()),
                AuthenticatedUser.id(jwt));
    }

    public record IndexDocumentRequest(@Size(max = 64) String documentId,
                                       @NotBlank @Size(max = 255) String title,
                                       @NotBlank @Size(max = 2048) String sourceUri,
                                       @NotBlank @Size(max = 64) String version,
                                       @Size(max = 64) String expectedVersion,
                                       @NotBlank @Size(max = 200000) String content) {
    }
}
