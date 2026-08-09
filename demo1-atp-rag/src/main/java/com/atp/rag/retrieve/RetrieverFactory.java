package com.atp.rag.retrieve;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.config.RagConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import io.qdrant.client.QdrantClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 按给定的消融配置造 {@link AtpRetriever}。
 *
 * <p>为什么需要这么一个工厂，而不是把 {@code AtpRetriever} 直接做成 bean：
 * <b>消融实验要在一次进程里造出六七个不同配置的检索器轮流跑</b>，
 * 而容器里的 bean 是单例。所以模型客户端（重、有连接、该复用）交给容器管，
 * 检索器（轻、随配置变）按需创建。
 *
 * <p>{@code ObjectProvider} 用来接收「可能不存在」的 bean ——
 * 没配 LLM key 时没有 {@code ChatLanguageModel}，关掉 rerank 时没有 {@code ScoringModel}。
 * 这两种缺失都是<b>正常运行状态</b>而不是错误：检索链路不需要 LLM，
 * 消融表前三行也不需要 rerank。
 */
@Component
public class RetrieverFactory {

    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;
    private final ObjectProvider<ScoringModel> scoringModelProvider;
    private final ObjectProvider<ChatLanguageModel> chatModelProvider;
    private final AtpProperties props;

    public RetrieverFactory(EmbeddingModel embeddingModel,
                            QdrantClient qdrantClient,
                            ObjectProvider<ScoringModel> scoringModelProvider,
                            ObjectProvider<ChatLanguageModel> chatModelProvider,
                            AtpProperties props) {
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
        this.scoringModelProvider = scoringModelProvider;
        this.chatModelProvider = chatModelProvider;
        this.props = props;
    }

    /** 按配置造检索器。配置里 rerank 关掉、或服务未注册时，自动退回纯向量检索。 */
    public AtpRetriever create(RagConfig config) {
        ScoringModel scoringModel = config.rerankEnabled()
                ? scoringModelProvider.getIfAvailable() : null;
        // 单 collection 模式下路由没有意义（两个「库」本来就是同一个），传 null 省一次判断
        AtpQueryRouter router = config.collectionMode() == RagConfig.CollectionMode.DUAL
                ? new AtpQueryRouter(chatModelProvider.getIfAvailable()) : null;
        return new AtpRetriever(config, embeddingModel, scoringModel, qdrantClient, router, props);
    }

    /** 默认配置（{@code application.yml} 里 {@code atp.rag.*} 的值，即全部优化都开）。 */
    public AtpRetriever createDefault() {
        return create(RagConfig.from(props));
    }

    public boolean isGenerationAvailable() {
        return chatModelProvider.getIfAvailable() != null;
    }

    public ChatLanguageModel chatModelOrNull() {
        return chatModelProvider.getIfAvailable();
    }

    public ScoringModel scoringModelOrNull() {
        return scoringModelProvider.getIfAvailable();
    }

    public AtpProperties properties() {
        return props;
    }
}
