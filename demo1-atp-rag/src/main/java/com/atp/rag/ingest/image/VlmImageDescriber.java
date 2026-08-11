package com.atp.rag.ingest.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.xml.bind.DatatypeConverter;

/**
 * 用视觉模型把截图转成文字描述。走 <b>OpenAI 兼容的 vision 协议</b>，
 * 所以本地起的 vLLM / Ollama / LM Studio，或者任何云端 VLM 都能接。
 *
 * <h3>当前状态：接口就绪，未部署模型</h3>
 *
 * <b>没有配 {@code atp.vlm.base-url} 时这个实现直接判定不可用</b>，
 * 入库会退回 {@link AltTextImageDescriber}，不影响流程。
 *
 * <p>为什么没部署：
 * <ul>
 *   <li>服务机余量 8.7G 显存，跑 Qwen2.5-VL-3B 够用，<b>技术上没有障碍</b></li>
 *   <li>但本项目语料是纯文本生成的，图片是为了演示这条链路而造的
 *       —— 为一条演示链路去部署、调通一个模型，投入产出比不划算</li>
 *   <li>而且 Blackwell（sm_120）的容器兼容性这个项目已经踩过一次（TEI 那个 CUDA compat 坑），
 *       再来一个模型就是再来一轮排查</li>
 * </ul>
 *
 * <p><b>真实项目里这一步是必须做的</b> —— ATP 的手册图很多，而且图里常常就是答案。
 * 所以这里把协议、prompt、降级、错误处理都写完整，
 * 接上一个真 VLM 只需要在 {@code .env} 里填一行 base-url。
 *
 * <h3>合规上的注意</h3>
 *
 * 图片要以 base64 塞进请求体。用云端 VLM 意味着<b>内部文档的截图出网</b>，
 * 这和本项目「embedding / rerank 本地跑、只有生成走 API」的合规前提是冲突的
 * —— 截图里可能有账号、内部地址、业务数据。
 * 所以真要上，应该优先本地 VLM，而不是图省事调云端。
 */
public final class VlmImageDescriber implements ImageDescriber {

    private static final Logger log = LoggerFactory.getLogger(VlmImageDescriber.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * 给 VLM 的指令。三条约束都是有针对性的：
     *
     * <ul>
     *   <li><b>只描述看得见的</b> —— 视觉模型很爱脑补「这可能是用于…」，
     *       那些推测会变成假的检索内容</li>
     *   <li><b>优先念出图里的文字</b> —— 截图里的按钮名、字段名、报错原文
     *       才是用户会拿来搜的词</li>
     *   <li><b>控制长度</b> —— 描述会被拼进 chunk，太长会稀释这个 chunk 原本的语义</li>
     * </ul>
     */
    private static final String PROMPT_TEMPLATE =
            "这是一张企业内部测试平台（ATP）文档里的插图，出现在「%s」这一节。\n"
                    + "请用一段不超过 80 字的中文描述它，供全文检索使用。要求：\n"
                    + "1. 只描述图中确实可见的内容，不要推测用途、不要补充背景知识；\n"
                    + "2. 把图中出现的文字（按钮名、字段名、报错信息、菜单项）原样念出来，"
                    + "这些词是用户实际会搜索的；\n"
                    + "3. 直接给描述，不要写「这张图显示了」之类的开场白。";

    private final OkHttpClient http;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Path corpusRoot;
    private final boolean configured;

    /**
     * @param baseUrl 为空表示没配 VLM，此实现直接不可用
     */
    public VlmImageDescriber(String baseUrl, String apiKey, String model,
                             String corpusDir, int timeoutSeconds) {
        this.configured = baseUrl != null && !baseUrl.trim().isEmpty();
        String trimmed = configured ? baseUrl.trim() : "";
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // OpenAI 兼容的 vision 走的就是普通的 chat completions 端点，
        // 区别只在 message content 是个数组、里面混着 text 和 image_url
        this.endpoint = trimmed.endsWith("/v1")
                ? trimmed + "/chat/completions" : trimmed + "/v1/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.corpusRoot = Paths.get(corpusDir);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // VLM 比纯文本慢得多，一张图几秒到几十秒都正常
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String describe(String imagePath, String altText, String context) {
        if (!configured) {
            return "";
        }
        // 图片文件读不到就别调模型了 —— 白花一次请求，而且错误信息会很难懂
        Path file = corpusRoot.resolve(imagePath).normalize();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("图片读不到，跳过 VLM 描述：{}（{}）", file, e.getMessage());
            return "";
        }

        try {
            String description = callVlm(bytes, imagePath, context);
            // 模型可能返回空串或纯空白，那和没描述是一回事
            return description.trim().isEmpty() ? "" : "［图片］" + description.trim();
        } catch (RuntimeException e) {
            // ⚠️ 单张图描述失败**不能**让整个入库失败。
            // 15 篇文档里有一张图调不通，不该导致 264 个点全部灌不进去 ——
            // 降级成没有描述，比整批失败合理得多
            log.warn("VLM 描述失败，降级为无描述：{}（{}）", imagePath, e.getMessage());
            return "";
        }
    }

    private String callVlm(byte[] imageBytes, String imagePath, String context) {
        // OpenAI vision 协议：content 是数组，text 和 image_url 两种 part 混排
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        // 描述图片是确定性任务，不需要发挥
        body.put("temperature", 0);
        body.put("max_tokens", 200);

        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");

        content.addObject()
                .put("type", "text")
                .put("text", String.format(PROMPT_TEMPLATE,
                        context == null || context.isEmpty() ? "未知章节" : context));

        // 图片以 data URI 内联。走 base64 而不是给个 URL，
        // 是因为语料在本地磁盘上，模型服务访问不到我们的文件系统
        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        imagePart.putObject("image_url")
                .put("url", "data:" + mimeTypeOf(imagePath) + ";base64,"
                        + DatatypeConverter.printBase64Binary(imageBytes));

        Request.Builder request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(body.toString(), JSON));
        // 本地起的 VLM 通常不设 key，云端才要
        if (!apiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        okhttp3.Response response = null;
        try {
            response = http.newCall(request.build()).execute();
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + "：" + text);
            }
            JsonNode json = MAPPER.readTree(text);
            return json.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            throw new IllegalStateException("调用 " + endpoint + " 失败", e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /** data URI 需要正确的 MIME，png 和 jpeg 混淆会被一些服务端拒绝。 */
    private static String mimeTypeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    @Override
    public boolean isAvailable() {
        return configured;
    }

    @Override
    public String name() {
        return "vlm:" + model;
    }
}
