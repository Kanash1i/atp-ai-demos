package com.atp.agent.tools;

import com.atp.agent.cli.AtpCliClient;
import com.atp.agent.cli.CliResult;
import com.atp.platform.entity.TcCase;
import com.atp.platform.mapper.TcCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 跑一次自验 —— 案例写完之后，让它真的在执行机上跑一遍。
 *
 * <h3>⭐ 只跑一次，不做「失败就改、改完再跑」的闭环</h3>
 *
 * 自动重试听起来很美，但有两个问题，任何一个都足以否掉它：
 *
 * <ol>
 *   <li><b>执行失败 ≠ 案例写错了。</b> 被测系统本身有 bug 时，
 *       自动改案例会把这个 bug「改没」—— 而发现 bug 正是测试的目的。</li>
 *   <li><b>改到能跑通 ≠ 改对了。</b> 让 agent 以「跑通」为目标，
 *       它最省力的路径是**削弱断言**：断言不了就删掉，等不到就放宽。
 *       测试变绿了，但什么也不保证了。</li>
 * </ol>
 *
 * <p>所以工具返回的文本是**刻意写成「报告用」而不是「修复用」**的 ——
 * 失败时它明确告诉模型：把现象讲给用户听，不要自己动手改。
 */
@Slf4j
@Component
public class ExecutionTools {

    /** 等一条案例跑完的上限。真跑通常几百毫秒到几秒，给足余量包含排队 */
    private static final int CASE_TIMEOUT_SEC = 120;

    @Autowired
    private AtpCliClient cli;

    /**
     * ⚠️ 读侧直接查库，不走 CLI —— 与写侧的原则不同：
     * 两边查的是同一批表，查询逻辑不同不会导致数据不一致。
     */
    @Autowired
    private TcCaseMapper caseMapper;

    @Tool(name = "run_case_once",
            description = "把已提交的案例在执行机上真跑一次，返回通过与否、失败在第几步、错误信息与录像。"
                    + "case 参数用**案例编号**（如 ATP-CART-0014，用户看到的就是它）；"
                    + "你自己刚提交的案例也可以直接传 caseId。"
                    + "⚠️ 只在案例已经 commit 之后用，而且只跑一次 —— "
                    + "这是给用户看的验证结果，不是让你反复调整直到跑通。")
    public String runCaseOnce(
            @ToolParam(name = "case",
                    description = "案例编号（ATP-CART-0014）或 caseId。用户说的一定是编号") String caseRef) {
        String caseId = resolveCaseId(caseRef);
        if (caseId == null) {
            return "找不到案例「%s」。用户说的应该是案例编号（形如 ATP-CART-0014）—— 确认编号是否正确，或用 find_similar_cases 查一下。"
                    .formatted(caseRef);
        }

        // ⚠️ 三层超时的层级：平台 run-once 等执行机 CASE_TIMEOUT_SEC，
        //    CLI 等平台 CASE_TIMEOUT_SEC+30，我等 CLI 再多 30 ——
        //    每一层都必须比它等的那层更有耐心，否则外层会把内层杀在半路，
        //    而 agent 收到的是「没拿到结论」，实际上再等十几秒就有了。
        CliResult r = cli.runWithin(CASE_TIMEOUT_SEC + 60,
                "run", caseId, "--timeout", String.valueOf(CASE_TIMEOUT_SEC));

        if (!r.success()) {
            // 没拿到结论 —— 环境问题，不是案例问题。这两件事绝不能混
            log.warn("[TOOL][run_case_once] {} 未拿到结论：{}", caseId, r.message());
            return """
                    没能拿到执行结果（%s）：%s

                    ⚠️ 这**不是**案例本身的问题，是执行环境没给出结论（多半是没有执行机在线）。
                    如实告诉用户「案例已提交，但当前验不了」，**不要因此去改案例**。"""
                    .formatted(r.code(), r.message().isBlank() ? "未知原因" : r.message());
        }

        String status = r.str("status");
        Integer failedSeq = r.data() != null && r.data().hasNonNull("failedSeq")
                ? r.data().get("failedSeq").asInt() : null;
        log.info("[TOOL][run_case_once] {} → {}", caseId, status);

        if ("PASSED".equals(status)) {
            return "执行通过 ✅  批次 %s，耗时 %d ms，录像：%s".formatted(
                    r.str("runCode"), r.intOr("durationMs", 0), nullSafe(r.str("videoUrl")));
        }

        // ⚠️ 这段文字是**刻意**引导「报告」而不是「修复」的。
        //    写成"请修正后重试"的话，模型会立刻开始改案例 —— 而它并不知道
        //    这次失败到底是案例写错了，还是被测系统真有毛病
        return """
                执行未通过 ❌  状态 %s%s
                耗时 %d ms，批次 %s
                错误：%s
                录像：%s

                ⚠️ 接下来**只做一件事：把上面的现象如实讲给用户听**，然后停下等他决定。

                不要自己改案例再跑一遍。原因：
                  1. 执行失败不等于案例写错了 —— 也可能是被测系统真有 bug，
                     而那正是这条案例该发现的东西。你把案例改到"能过"，就把 bug 盖住了。
                  2. 改到能跑通不等于改对了 —— 最省力的改法是削弱断言，
                     那样测试会变绿，但它什么也不保证了。

                如果你对失败原因有判断（比如定位器可能不对），可以说出来**作为建议**，
                但要明确区分「我观察到的」和「我猜测的」，由用户决定改不改。"""
                .formatted(status,
                        failedSeq == null ? "" : "，失败在第 " + failedSeq + " 步",
                        r.intOr("durationMs", 0), r.str("runCode"),
                        firstLine(r.str("errorMsg")), nullSafe(r.str("videoUrl")));
    }

    /** Playwright 的堆栈能有几十行，模型和人都只看得下第一行 */
    /**
     * 把「用户说的东西」解析成 caseId。
     *
     * <h3>⚠️ 为什么不能只收 caseId</h3>
     *
     * caseId 是 UUID 主键，**用户界面上根本看不到它** —— 案例中心显示的是
     * ATP-CART-0014 这样的编号。工具只收 caseId 的话，用户说「跑一下 ATP-CART-0014」，
     * agent 只能反问「请提供 caseId」，而那是个用户拿不到的东西。
     *
     * <p><b>工具的参数应该是用户语言里存在的东西，不是数据库主键。</b>
     *
     * <p>两种都认：agent 刚提交完案例时手上有 caseId，直接传省一次查询；
     * 用户说的一定是编号。靠形状区分 —— UUID 是 36 位 5 段，编号形如 ATP-XXX-NNNN。
     */
    private String resolveCaseId(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String v = ref.trim();

        // UUID 形状：直接当 caseId，但仍确认存在 —— 不存在的话下游 CLI 报的错更难懂
        if (v.length() == 36 && v.chars().filter(c -> c == '-').count() == 4) {
            return caseMapper.selectById(v) == null ? null : v;
        }

        TcCase c = caseMapper.selectOne(new LambdaQueryWrapper<TcCase>()
                .eq(TcCase::getCaseCode, v.toUpperCase()).last("LIMIT 1"));
        return c == null ? null : c.getCaseId();
    }

    private String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "（无错误信息）";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl) + " …";
    }

    private String nullSafe(String s) {
        return s == null ? "（无）" : s;
    }
}
