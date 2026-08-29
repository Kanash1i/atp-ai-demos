# demo1 归档（Java 8 + langchain4j，2026-08-19 停工）

> ⚠️ **这是归档，不是活代码。** demo1 停在 M3、消融表一行没跑出来，
> 知识侧改为在买来的 Java 21 引擎上重做 —— 见 `../../03-HANDOFF-rag-v2.md`。
>
> **这里只留 `03-HANDOFF-rag-v2.md` §3 点名要保留的东西**，
> 58 个 Java 源文件、pom、构建产物已物理删除（在 git 历史里可回溯）。
> 留着它们的唯一后果是：下一个开工的人会打开那个同名目录。

## 内容

| | 依据 | 说明 |
|---|---|---|
| `DECISIONS.md` | §3.1 | **26 条决策，面试素材库。即使代码不用了，坑还是你踩的** |
| `corpus/docs/` | §3.1 | 15 篇文档。**中日混排是刻意设计**，跨语言评估靠它 |
| `corpus/cases/` | §3.1 | 80 条案例 JSON，**含约 15 条刻意植入的脏数据** |
| `corpus/docs-pdf/`、`docs-docx/` | —— | 手搓生成器的产物。生成器本身作废（MinerU 取代），但**这些文件本身是喂给 MinerU 的现成输入** |
| `tools/gen_cases.py` | §3.1 | 语料生成器，要调分布时重新生成 |
| `reference/` | §3.3 | 三处"看一眼再决定"的实现，见下 |

## `reference/` 里那三个文件为什么留着

`03-HANDOFF-rag-v2.md` §3.3 明确说这三处**看一眼再决定**，不是直接搬：

- **`TeiScoringModel.java`** —— rerank 适配器。若新引擎自带 rerank 客户端就用它的，
  但必须确认两件事：TEI 响应 `[{index,score}]` **未排序**、有 **batch 上限 32**。
  **这两个都是静默错误点** —— 不报错，只是结果悄悄不对。
- **`HeadingPathSplitter.java`** + **`SplitterTest.java`** —— 按 Markdown 标题的层级切块。
  若 MinerU 输出的层级干净，新引擎的切分可能已够用；不够时这是现成的参考实现。

## ⚠️ 语料重灌时别弄丢的三个设计点（§3.2 原文）

1. **语言分布是刻意的**：手册主体中文，`08` 用日文；STD-001/004/005 用日文；FAQ 中日混排。
   → 支撑评估集 C 类「中文提问召回日文文档」，**纯中文 demo 做不出这个展示点**。
2. **80 条案例里植入了约 15 条脏数据**：3 条用 `SLEEP`、4 条绝对路径 XPath、
   3 条依赖动态 id、2 条无断言、3 条 `case_code` 不合规。
   → 支撑「不能无脑推荐存量案例，得能指出『这条结构可参考，但等待策略违反 STD-004，别照抄』」。
   **这是从「检索」升级到「有判断力的助手」的关键。**
3. **案例 metadata 进 payload**：`module_code, priority, actions_used[], has_violation,
   violation_codes[], step_count`。
