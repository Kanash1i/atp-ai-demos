package com.atp.rag;

import com.atp.rag.ingest.CorpusIngestService;
import com.atp.rag.tei.TeiEmbeddingModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.PgVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真正把语料灌进 pgvector。
 *
 * <p>做成测试而不是 CLI 命令，是因为它需要的全部依赖（embedding、向量库）
 * 都能在这里手工装配 —— 起一个完整 Spring 上下文只为跑一次导入不划算。
 * 由 {@code scripts/ingest-corpus.sh} 调用。
 */
class CorpusIngestRunTest {

    @Test
    @DisplayName("导入 seed/docs 的全部 markdown")
    void ingest() throws Exception {
        String dbUrl = System.getenv("ATP_DB_URL");
        assumeTrue(dbUrl != null && !dbUrl.isBlank(), "ATP_DB_URL 未设置，跳过");

        String seedDir = System.getenv().getOrDefault("ATP_SEED_DIR", "../../seed");
        String embedUrl = System.getenv().getOrDefault(
                "EMBEDDING_BASE_URL", "http://192.168.0.101:8081") + "/v1";

        PgVectorStore store = PgVectorStore.builder()
                .jdbcUrl(dbUrl)
                .username(System.getenv("ATP_DB_USER"))
                .password(System.getenv("ATP_DB_PASSWORD"))
                .tableName("rag_vector").dimensions(1024)
                .distanceType(PgVectorStore.DistanceType.COSINE)
                .build();

        Knowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(new TeiEmbeddingModel(embedUrl,
                        System.getenv().getOrDefault("EMBEDDING_MODEL", "bge-m3"), 1024))
                .embeddingStore(store)
                .build();

        CorpusIngestService service = new CorpusIngestService();
        ReflectionTestUtils.setField(service, "knowledge", knowledge);
        ReflectionTestUtils.setField(service, "corpusDir", seedDir + "/docs");

        int chunks = service.ingestAll();
        System.out.println("  导入切块数: " + chunks);
        assertTrue(chunks > 0, "一个块都没导入");
    }
}
