package com.atp.rag.config;

import io.agentscope.core.embedding.EmbeddingModel;
import com.atp.rag.tei.TeiEmbeddingModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.store.PgVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库：本地 TEI 做 embedding，pgvector 存向量。
 *
 * <h3>embedding 走自己实现的 TEI 客户端</h3>
 *
 * 模型跑在自己的显卡上，不出网、不计费、不受配额限制。
 * 框架自带的 {@code OpenAITextEmbedding} 用不了 —— 它引用了 agentscope 没声明的依赖，
 * 详见 {@link TeiEmbeddingModel} 的类注释。
 *
 * <p>⚠️ 开工前必须确认 TEI 真的在 GPU 上：{@code docker logs tei-embed | grep "model on"}
 * 必须是 <b>Cuda</b>。这个项目栽过一次 —— 服务 health 200、维度也对，
 * 实际却在 CPU 上跑，靠风扇声才发现。
 *
 * <h3>为什么 pgvector 而不是专用向量库</h3>
 *
 * AgentScope 自带 {@link PgVectorStore}，**它自己建表和索引**，
 * 与业务表同库意味着少一个要部署、要备份、要监控的服务。
 * 语料量（15 篇文档 + 80 条案例）也根本用不上专用向量库。
 */
@Slf4j
@Configuration
public class RagConfig {

    @Value("${atp.rag.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${atp.rag.embedding.model}")
    private String embeddingModel;

    @Value("${atp.rag.embedding.dimensions:1024}")
    private int dimensions;

    @Value("${atp.rag.store.table:rag_vector}")
    private String tableName;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Bean
    public EmbeddingModel atpEmbeddingModel() {
        log.info("Embedding 就绪 {} @ {}（{} 维）", embeddingModel, embeddingBaseUrl, dimensions);
        // ⚠️ 不用框架的 OpenAITextEmbedding：它引用了 agentscope 自己没声明的
        //    openai-java 依赖，运行期 ClassNotFoundException。详见 TeiEmbeddingModel 类注释
        return new TeiEmbeddingModel(embeddingBaseUrl, embeddingModel, dimensions);
    }

    /**
     * 向量存储。
     *
     * <p>⚠️ 距离用 COSINE —— bge-m3 输出的是归一化向量，余弦与点积等价。
     * 用 COSINE 是因为它对未归一化的向量也成立，将来换 embedding 模型时不用回来改这里。
     */
    @Bean
    public PgVectorStore atpVectorStore() throws VectorStoreException {
        // ⚠️ build() 会当场连库并建表建索引，所以它抛的是受检异常。
        //    让它往上抛而不是吞掉：库连不上时应用就该起不来 ——
        //    一个"启动成功但检索永远返回空"的服务，比起不来难查得多。
        log.info("向量表 {}（pgvector，COSINE）", tableName);
        return PgVectorStore.builder()
                .jdbcUrl(jdbcUrl)
                .username(dbUser)
                .password(dbPassword)
                .tableName(tableName)
                .dimensions(dimensions)
                .distanceType(PgVectorStore.DistanceType.COSINE)
                .build();
    }

    @Bean
    public Knowledge atpKnowledge(EmbeddingModel embedding, PgVectorStore store) {
        return SimpleKnowledge.builder()
                .embeddingModel(embedding)
                .embeddingStore(store)
                .build();
    }
}
