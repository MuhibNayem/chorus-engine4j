package com.chorus.engine.springboot;

import com.chorus.engine.rag.document.Document;
import com.chorus.engine.rag.pipeline.RAGPipeline;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for RAG operations.
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RAGPipeline ragPipeline;

    public RagController(RAGPipeline ragPipeline) {
        this.ragPipeline = ragPipeline;
    }

    /**
     * Query the RAG pipeline.
     */
    @PostMapping("/query")
    public ResponseEntity<RagQueryResponse> query(@RequestBody RagQueryRequest request) {
        var result = ragPipeline.query(
            request.query(),
            new com.chorus.engine.rag.retrieval.RetrievalEngine.RetrieveOptions(
                request.topK() != null ? request.topK() : 5,
                request.filters() != null ? request.filters() : Map.of(),
                true
            )
        );

        return ResponseEntity.ok(new RagQueryResponse(
            result.answer(),
            result.originalQuery(),
            result.citations().stream()
                .map(c -> new CitationDto(c.id(), c.chunk().text(), c.chunk().documentId()))
                .toList()
        ));
    }

    /**
     * Ingest a document into the RAG pipeline.
     */
    @PostMapping("/ingest")
    public ResponseEntity<RagIngestResponse> ingest(@RequestBody RagIngestRequest request) {
        Document document = Document.builder(request.documentId(), request.content(), request.source())
            .title(request.title())
            .metadata(request.metadata() != null ? request.metadata() : Map.of())
            .build();

        var chunks = ragPipeline.ingest(document);

        return ResponseEntity.ok(new RagIngestResponse(
            request.documentId(),
            chunks.size(),
            "Ingested successfully"
        ));
    }

    public record RagQueryRequest(
        String query,
        Integer topK,
        Map<String, Object> filters
    ) {}

    public record RagQueryResponse(
        String answer,
        String query,
        List<CitationDto> citations
    ) {}

    public record CitationDto(
        int id,
        String text,
        String documentId
    ) {}

    public record RagIngestRequest(
        String documentId,
        String content,
        String source,
        String title,
        Map<String, Object> metadata
    ) {}

    public record RagIngestResponse(
        String documentId,
        int chunksIngested,
        String status
    ) {}
}
