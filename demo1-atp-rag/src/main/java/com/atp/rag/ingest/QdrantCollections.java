package com.atp.rag.ingest;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;

/**
 * collection 的建立与重建。
 *
 * <p>入库一律走「先删再建」而不是增量 upsert。理由是这个 demo 的语料是<b>全量生成</b>的，
 * 增量更新会让残留的旧 chunk 混进检索结果 —— 切分策略一改，旧 chunk 的边界就不对了，
 * 但它们还躺在库里，照样会被召回。那种脏数据在评估里表现为「某几个 query 莫名其妙地差」，
 * 极难定位。
 *
 * <p>（生产环境是另一回事：案例每天新增，全量重建不现实。这是面试预演问题第 4 条，
 * 答案见 README —— 按 case_id 做 upsert + 软删标记，而不是照搬这里的做法。）
 */
public final class QdrantCollections {

    private QdrantCollections() {
    }

    /** 删掉重建，保证 collection 里只有本次入库写进去的数据。 */
    public static void recreate(QdrantClient client, String name, int dimension) {
        drop(client, name);
        try {
            client.createCollectionAsync(name, VectorParams.newBuilder()
                    .setSize(dimension)
                    // bge-m3 的向量是归一化的，Cosine 与点积等价。
                    // 用 Cosine 是因为它对未归一化的向量也成立，换 embedding 模型时不用回来改这里
                    .setDistance(Distance.Cosine)
                    .build()).get();
        } catch (Exception e) {
            throw new IllegalStateException("建 collection " + name + " 失败", e);
        }
    }

    /** 先查存在再删 —— 直接删不存在的 collection，qdrant client 会自己打一条 ERROR 日志。 */
    public static void drop(QdrantClient client, String name) {
        try {
            if (client.collectionExistsAsync(name).get()) {
                client.deleteCollectionAsync(name).get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("删除 collection " + name + " 失败", e);
        }
    }

    public static long countPoints(QdrantClient client, String name) {
        try {
            return client.countAsync(name).get();
        } catch (Exception e) {
            throw new IllegalStateException("统计 collection " + name + " 的点数失败", e);
        }
    }
}
