package com.atp.rag.storage;

/**
 * 原图的对象存储。抽出来的图片存到这里，换回一个可访问的地址。
 *
 * <h3>为什么图转文字之后还要留原图</h3>
 *
 * 因为<b>描述文本是有损的</b>。VLM 把一张报错截图描述成
 * 「案例编辑页的步骤表单，wait_strategy 字段显示 CLICKABLE 且置灰」——
 * 用来检索够了，但用户看到这条引用时想确认的是「界面到底长什么样」。
 *
 * <p>所以 payload 里两样都存：
 * <ul>
 *   <li><b>描述文本</b> —— 进 embedding，让图片内容可被检索到</li>
 *   <li><b>原图地址</b> —— 不进 embedding，只在引用展示时给出，让人能点开看</li>
 * </ul>
 *
 * <p>这也是 RAG 里处理非文本内容的通行做法：<b>检索用它的文字投影，
 * 呈现用它的原件</b>。
 *
 * <h3>为什么是接口</h3>
 *
 * 本地跑 demo 用文件系统就够了，但企业里这东西一定在对象存储上
 * （阿里云 OSS / S3 / MinIO），因为入库进程和查询进程通常不在同一台机器上 ——
 * 本地路径在另一端打不开。
 *
 * <p>把它做成接口，切换只是换一个 bean：解析、切分、入库这些代码
 * 完全不知道图片存在哪。
 */
public interface ObjectStorage {

    /**
     * 存一个对象，返回可访问的地址。
     *
     * <p>实现应当是<b>幂等</b>的：同样的 {@code key} 重复调用不该产生多份副本。
     * 入库会被反复重跑（消融实验每组配置跑一遍），不幂等的话图片会成倍堆积。
     *
     * @param key         对象的逻辑路径，如 {@code images/manual/05-等待策略/img-1.png}。
     *                    由调用方保证唯一且稳定 —— 稳定才能幂等
     * @param content     对象字节
     * @param contentType MIME 类型，如 {@code image/png}
     * @return 可访问地址。本地实现返回配置的 base-url 拼上 key；
     *         OSS 实现返回对象 URL 或签名 URL
     */
    String put(String key, byte[] content, String contentType);

    /** 这个存储的简短描述，启动时打进日志 —— 免得不知道图片到底存哪去了。 */
    String describe();
}
