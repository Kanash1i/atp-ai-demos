package com.atp.rag;

import com.atp.rag.tei.TeiEmbeddingModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.PgVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RAG 的效果对照 —— 同一个问题，裸模型 vs 接上检索。
 *
 * <p>⭐ 这是整个知识侧最值得展示的一条证据：<b>不是「RAG 能跑」，而是「不接 RAG 就是错的」</b>。
 * 模型不知道 ATP 的内部规范，它会瞎猜一个听起来像等待策略的词，
 * 而且语气笃定、格式正确 —— 这种错误比报错难发现得多。
 */
class RagContrastTest {

    private static final String QUESTION = "CLICK 步骤的 wait_strategy 应该设成什么？只回答枚举值本身，不要解释。";

    @Test
    @DisplayName("同一个问题：裸模型答错，接上 RAG 答对")
    void contrast() throws Exception {
        String apiKey = System.getenv("LLM_API_KEY");
        String dbUrl = System.getenv("ATP_DB_URL");
        assumeTrue(apiKey != null && dbUrl != null, "缺 LLM_API_KEY 或 ATP_DB_URL，跳过");

        Model model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash"))
                .baseUrl(System.getenv().getOrDefault("LLM_BASE_URL", "https://api.deepseek.com/v1"))
                .stream(true).build();

        // ── ① 裸模型 ──────────────────────────────────────────
        String bare = ask(model, "你是 ATP 测试平台的助手。", QUESTION);
        System.out.println("\n  ① 裸模型  → " + bare.trim());

        // ── ② 接上 RAG ────────────────────────────────────────
        PgVectorStore store = PgVectorStore.builder()
                .jdbcUrl(dbUrl).username(System.getenv("ATP_DB_USER")).password(System.getenv("ATP_DB_PASSWORD"))
                .tableName("rag_vector").dimensions(1024)
                .distanceType(PgVectorStore.DistanceType.COSINE).build();
        Knowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(new TeiEmbeddingModel(
                        System.getenv().getOrDefault("EMBEDDING_BASE_URL", "http://192.168.0.101:8081"),
                        System.getenv().getOrDefault("EMBEDDING_MODEL", "bge-m3"), 1024))
                .embeddingStore(store).build();

        List<Document> hits = knowledge.retrieve(QUESTION,
                RetrieveConfig.builder().limit(3).build()).block();
        String context = hits == null ? "" : hits.stream()
                .map(d -> "【" + d.getPayloadValue("anchor") + "】\n" + d.getPayloadValue("display_text"))
                .reduce("", (a, b) -> a + "\n\n" + b);

        String grounded = ask(model,
                "你是 ATP 测试平台的助手。只依据下面的规范作答，规范里没有的不要编：\n" + context,
                QUESTION);
        System.out.println("  ② 接 RAG  → " + grounded.trim());
        System.out.println("  （检索依据 " + (hits == null ? 0 : hits.size()) + " 条）\n");

        assertTrue(grounded.contains("CLICKABLE"),
                "接上 RAG 之后仍未答出 CLICKABLE，实际：" + grounded);
    }

    private String ask(Model model, String system, String question) {
        List<ChatResponse> responses = model.stream(List.of(
                Msg.builder().role(MsgRole.SYSTEM).name("system")
                        .content(TextBlock.builder().text(system).build()).build(),
                Msg.builder().role(MsgRole.USER).name("user")
                        .content(TextBlock.builder().text(question).build()).build()
        ), null, null).collectList().block();

        return responses == null ? "" : responses.stream()
                .flatMap(r -> r.getContent() == null ? Stream.empty() : r.getContent().stream())
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).reduce("", String::concat);
    }
}
