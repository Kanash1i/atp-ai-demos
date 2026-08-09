package com.atp.rag.task;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 没指定 {@code --atp.task} 时打印用法。
 *
 * <p>这样启动时不会静默什么都不做 —— 一个跑完就退出、既不报错也没有输出的应用，
 * 是最容易让人以为「坏了」的状态。
 *
 * <p>⚠️ 这里刻意<b>不用</b> {@code @ConditionalOnProperty(havingValue = "", matchIfMissing = true)}。
 * 那个组合看起来是「属性缺失或为空」，实际语义完全不同 ——
 * 空的 {@code havingValue} 等价于「未指定」，而未指定时的规则是
 * <b>「属性存在且不等于 false」就匹配</b>。结果 {@code --atp.task=spike} 也会命中，
 * 用法提示会跟在每个任务后面一起打印出来（实测踩到了）。
 * 用 SpEL 判断空串才是真正想要的语义。
 */
@Component
@ConditionalOnExpression("'${atp.task:}'.trim().isEmpty()")
public class UsageTask implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        System.out.println();
        System.out.println("ATP 知识助手 —— 需要用 --atp.task 指定要跑的任务");
        System.out.println();
        System.out.println("  spike   环境自检（embedding / Qdrant / rerank 四项检查）");
        System.out.println("  ingest  语料入库，一次建好消融实验需要的全部 collection");
        System.out.println("  demo    非交互跑批，覆盖评估集四类用例");
        System.out.println("  cli     交互式问答（面试演示用）");
        System.out.println("  probe   检索对比探针（开发期快速反馈）");
        System.out.println();
        System.out.println("例：mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=spike");
        System.out.println();
        System.out.println("消融开关可从命令行覆盖，例如：");
        System.out.println("  --atp.rag.chunk-strategy=FIXED --atp.rag.collection-mode=SINGLE");
        System.out.println("  --atp.rerank.enabled=false");
        System.out.println();
    }
}
