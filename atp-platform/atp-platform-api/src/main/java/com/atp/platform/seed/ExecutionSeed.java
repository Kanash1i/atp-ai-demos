package com.atp.platform.seed;

import com.atp.common.util.DisplayTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 执行看板的历史数据。
 *
 * <h3>为什么历史是种子、执行是真的</h3>
 *
 * 「今日执行 1,284 次 / 通过率 94.2% / 最近 200 条」这类数字，靠现场真跑 Playwright 攒不出来 ——
 * 但它们正是这一屏要展示的东西。所以分成两半：
 * <ul>
 *   <li><b>历史</b>（这个类）：三天的执行记录，让看板有底数</li>
 *   <li><b>现场派发</b>（M2 的 atp-runner）：真跑、真录像、真进度</li>
 * </ul>
 *
 * <p>⭐ 看板上的统计**从表里 count 出来**，不是硬编码的常量 ——
 * 被问「这个 94.2% 怎么来的」时，答案是一条能当场跑给人看的 SQL。
 *
 * <p>⚠️ 用 {@link JdbcTemplate#batchUpdate} 批量插 —— 三千多行如果一条条 insert，
 * 每条都是一次到台式机的网络往返，光这一步就要几十秒。
 *
 * <h3>⚠️ 种子会过期，所以每次启动都重造</h3>
 *
 * 数据是按「造它那一刻的今天」生成的 —— 跨过零点之后，「今日执行」就变成 0，看板一夜之间空掉。
 * 所以 {@link #refresh()} 在每次启动（以及每天零点）把种子按当前时刻重造一遍。
 *
 * <p>重造**只删种子**（{@code exec_run.is_seed = 1}），M2 起 Playwright 真跑出来的记录永不动 ——
 * 那些是真实发生过的事，删掉就等于伪造历史。
 *
 * <p>随机数种子是固定的，所以每次重造出来的案例分布、失败分布完全一样，只有时间轴整体平移。
 * 演示时截的图和实际跑出来的对得上。
 */
@Slf4j
@Service
public class ExecutionSeed {

    /** 固定种子，保证每次重建库造出来的数字一样 —— 演示时截图和实际对得上 */
    private static final Random RANDOM = new Random(20260829L);

    private static final int NODE_COUNT = 8;
    /** 设计稿里是 6/8 在线，剩下两个当作掉线 */
    private static final int ONLINE_NODES = 6;

    /** 三天的执行量。今日比昨日多 7.4%，与设计稿的环比一致 */
    private static final int[] DAILY_VOLUME = {1100, 1196, 1284};

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 把演示用的执行历史刷新到「当前时刻」。
     *
     * <p>每次启动调用，不需要 {@code --seed} 开关 —— 它不是「导入数据」，是「让数据别过期」。
     *
     * @return 重造出来的任务条数
     */
    @Transactional
    public int refresh() {
        int removed = purgeSeed();
        if (removed > 0) {
            log.info("清掉 {} 个过期的种子批次，按当前时刻重造", removed);
        }
        return importHistory();
    }

    /**
     * 只删种子批次及其子记录。
     *
     * <p>⚠️ 顺序不能反：先删孙表 exec_step_result、再删 exec_task、最后 exec_run。
     * 本库不建外键约束（D-109），数据库不会拦着你留下孤儿行 —— 顺序错了就是脏数据，还不报错。
     */
    private int purgeSeed() {
        jdbc.update("""
                DELETE FROM exec_step_result
                WHERE task_id IN (SELECT task_id FROM exec_task
                                  WHERE run_id IN (SELECT run_id FROM exec_run WHERE is_seed = 1))
                """);
        jdbc.update("""
                DELETE FROM exec_task
                WHERE run_id IN (SELECT run_id FROM exec_run WHERE is_seed = 1)
                """);
        // 节点也一并重来 —— 心跳过期的话看板上会显示八个全离线
        jdbc.update("DELETE FROM exec_node");
        return jdbc.update("DELETE FROM exec_run WHERE is_seed = 1");
    }

    @Transactional
    public int importHistory() {
        List<CaseRef> cases = loadCases();
        if (cases.isEmpty()) {
            log.warn("库里没有案例，跳过执行历史 —— 先导入案例种子");
            return 0;
        }

        seedNodes();

        OffsetDateTime todayStart = OffsetDateTime.now(DisplayTime.ZONE)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);

        List<Object[]> runRows = new ArrayList<>();
        List<Object[]> taskRows = new ArrayList<>();
        List<Object[]> stepRows = new ArrayList<>();

        for (int dayOffset = 0; dayOffset < DAILY_VOLUME.length; dayOffset++) {
            // dayOffset=0 是前天，最后一个是今天
            int daysAgo = DAILY_VOLUME.length - 1 - dayOffset;
            OffsetDateTime dayStart = todayStart.minusDays(daysAgo);
            int volume = DAILY_VOLUME[dayOffset];

            // 一天切成 12 个批次，模拟「定时跑 + 人工跑 + agent 跑」混在一起。
            // ⚠️ 批次数不能太少：5 个批次时「最近 200 条」全落在同一批里，
            //    浏览器列全是一种、时间戳挤在 8 分钟内 —— 一眼就看得出是造的。
            int batches = 12;

            // ⭐ 先定这一天的可用时间窗，再把批次均匀铺进去。
            //
            // ⚠️ 今天的窗口是 [今天00:00, 现在-5分钟]，**不是固定的 14 小时** ——
            //    凌晨 1 点重启时，「今天」只过了一个小时，按固定间隔铺的话
            //    大半批次会落回昨天，「今日执行」还是不对。
            //    窗口自适应之后，无论几点启动，1284 条都落在今天，只是密疏不同。
            OffsetDateTime windowStart;
            OffsetDateTime windowEnd;
            if (daysAgo == 0) {
                windowEnd = OffsetDateTime.now().minusMinutes(5);
                windowStart = dayStart;
                // 刚过零点的兜底：窗口不足 20 分钟就把它撑到 20 分钟，
                // 宁可让最早几条压在零点附近，也不要溢出到昨天
                if (Duration.between(windowStart, windowEnd).toMinutes() < 20) {
                    windowStart = windowEnd.minusMinutes(20);
                }
            } else {
                // 历史日子按上班时间铺开
                windowStart = dayStart.plusHours(8);
                windowEnd = dayStart.plusHours(22);
            }
            long windowSec = Math.max(Duration.between(windowStart, windowEnd).getSeconds(), 60L);

            for (int b = 0; b < batches; b++) {
                int size = volume / batches + (b == 0 ? volume % batches : 0);
                OffsetDateTime batchStart = windowStart.plusSeconds(windowSec * b / batches);
                OffsetDateTime batchEnd = windowStart.plusSeconds(windowSec * (b + 1) / batches);
                buildRun(batchStart, batchEnd, daysAgo, b, size, cases, runRows, taskRows, stepRows);
            }
        }

        jdbc.batchUpdate("""
                INSERT INTO exec_run (run_id, run_code, project_id, suite_name, browser, status,
                                      total_count, passed_count, failed_count, skipped_count, running_count,
                                      trigger_source, created_by, started_at, finished_at, created_at, is_seed)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)
                """, runRows);
        jdbc.batchUpdate("""
                INSERT INTO exec_task (task_id, run_id, case_id, case_code, case_title, browser, node_name,
                                       status, duration_ms, error_msg, failed_seq, video_url, screenshot_url,
                                       queued_at, started_at, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, taskRows);
        jdbc.batchUpdate("""
                INSERT INTO exec_step_result (result_id, task_id, seq, action, status, duration_ms, error_msg, screenshot_url)
                VALUES (?,?,?,?,?,?,?,?)
                """, stepRows);

        log.info("执行历史导入完成：{} 个批次 / {} 条任务 / {} 条步骤结果",
                runRows.size(), taskRows.size(), stepRows.size());
        return taskRows.size();
    }

    // ── 构造 ──────────────────────────────────────────────────

    /**
     * 造一个批次，任务在 {@code [batchStart, batchEnd)} 内均匀铺开。
     *
     * <p>⚠️ 任务间隔由窗口除以条数算出来，不是写死的秒数 ——
     * 窗口窄（凌晨启动）时自动变密，窗口宽（傍晚）时自动铺开，
     * 两种情况下最后一条都不会溢出到窗口之外。
     */
    private void buildRun(OffsetDateTime batchStart, OffsetDateTime batchEnd,
                          int daysAgo, int batchIndex, int size,
                          List<CaseRef> cases,
                          List<Object[]> runRows, List<Object[]> taskRows, List<Object[]> stepRows) {
        String runId = UUID.randomUUID().toString();
        OffsetDateTime startedAt = batchStart;
        // 每条任务之间的间隔。至少 1 秒，否则一批任务会挤在同一时刻，排序看不出先后
        long stepSec = Math.max(1L, Duration.between(batchStart, batchEnd).getSeconds() / Math.max(size, 1));
        String runCode = "RUN-%s-%04d".formatted(
                startedAt.atZoneSameInstant(DisplayTime.ZONE).toLocalDate().toString().replace("-", ""),
                100 + batchIndex);

        short browser = pickBrowser();
        // 触发来源：定时为主，掺人工与 agent —— 看板可以按它分组对比两条路线
        short trigger = switch (batchIndex % 4) {
            case 0, 2 -> 3;   // SCHEDULED
            case 3 -> 2;      // AGENT
            default -> 1;     // MANUAL
        };
        String createdBy = trigger == 2 ? "agent" : (trigger == 3 ? "scheduler" : pickAuthor());

        int passed = 0, failed = 0, skipped = 0;
        int cursor = 0;

        for (int i = 0; i < size; i++) {
            CaseRef c = cases.get(RANDOM.nextInt(cases.size()));
            // 94% 通过 / 5% 失败 / 1% 跳过 —— 与设计稿的通过率一档
            int roll = RANDOM.nextInt(100);
            short status;
            if (roll < 94) {
                status = 3; passed++;
            } else if (roll < 99) {
                status = 4; failed++;
            } else {
                status = 5; skipped++;
            }

            // 耗时围绕 38 秒抖动。SKIPPED 没有耗时
            Integer duration = status == 5 ? null : Math.max(3_000, (int) (38_000 + RANDOM.nextGaussian() * 12_000));
            OffsetDateTime queued = startedAt.plusSeconds(cursor);
            cursor += stepSec;
            OffsetDateTime started = queued.plusSeconds(1);
            OffsetDateTime finished = duration == null ? started : started.plusNanos(duration * 1_000_000L);

            String taskId = UUID.randomUUID().toString();
            String node = "node-%02d".formatted(1 + RANDOM.nextInt(ONLINE_NODES));
            Integer failedSeq = status == 4 ? 1 + RANDOM.nextInt(5) : null;
            String errorMsg = status == 4 ? pickError() : null;

            // ⚠️ 录像与截图只有失败的和抽样的通过用例才留 —— 真实平台不会给每次执行都存视频。
            //    M2 的 Playwright runner 会往同一个 URL 形态里写真文件。
            String video = (status == 4 || RANDOM.nextInt(10) == 0)
                    ? "/api/artifacts/%s/%s.webm".formatted(runCode, c.caseCode) : null;
            String shot = status == 4
                    ? "/api/artifacts/%s/%s-fail.png".formatted(runCode, c.caseCode) : null;

            taskRows.add(new Object[]{taskId, runId, c.caseId, c.caseCode, c.title, browser, node,
                    status, duration, errorMsg, failedSeq, video, shot, queued, started, finished});

            // ⚠️ 今天的失败任务**全部**造步骤明细，不设上限：
            //    「点 FAIL 进失败详情」是演示动作，随手点一条却是空的就砸了。
            //    历史两天的不造 —— 那些只用来撑统计数字，没人会去点。
            //    今天失败约 76 条 × 4 步 ≈ 300 行，代价可以忽略。
            if (status == 4 && daysAgo == 0) {
                buildStepResults(taskId, failedSeq, errorMsg, stepRows);
            }
        }

        OffsetDateTime runFinished = batchEnd;
        runRows.add(new Object[]{runId, runCode, "P001", pickSuite(batchIndex), browser, (short) 3,
                size, passed, failed, skipped, 0, trigger, createdBy, startedAt, runFinished, startedAt});
    }

    /** 失败任务的步骤明细：失败那一步之前全绿，之后全 SKIPPED（on_failure=ABORT 的效果） */
    private void buildStepResults(String taskId, Integer failedSeq, String errorMsg, List<Object[]> out) {
        int total = Math.max(failedSeq == null ? 3 : failedSeq + 1, 3);
        for (int seq = 1; seq <= total; seq++) {
            short status;
            String err = null;
            if (failedSeq != null && seq == failedSeq) {
                status = 2; err = errorMsg;
            } else if (failedSeq != null && seq > failedSeq) {
                status = 3;
            } else {
                status = 1;
            }
            String shot = status == 2 ? "/api/artifacts/step-%d-fail.png".formatted(seq) : null;
            out.add(new Object[]{UUID.randomUUID().toString(), taskId, seq,
                    pickAction(seq), status, status == 3 ? null : 200 + RANDOM.nextInt(4000), err, shot});
        }
    }

    private void seedNodes() {
        List<Object[]> rows = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 1; i <= NODE_COUNT; i++) {
            boolean online = i <= ONLINE_NODES;
            rows.add(new Object[]{
                    UUID.randomUUID().toString(),
                    "node-%02d".formatted(i),
                    (short) (online ? 1 : 3),
                    1,
                    null,
                    // ⚠️ 掉线的节点心跳停在 20 分钟前 —— 在线判定看心跳，不看 status 列
                    online ? now : now.minusMinutes(20),
                    now.minusDays(30)});
        }
        jdbc.batchUpdate("""
                INSERT INTO exec_node (node_id, node_name, status, capacity, current_task_id, heartbeat_at, registered_at)
                VALUES (?,?,?,?,?,?,?)
                """, rows);
    }

    private List<CaseRef> loadCases() {
        return jdbc.query("SELECT case_id, case_code, title FROM tc_case WHERE case_code IS NOT NULL",
                (rs, i) -> new CaseRef(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    private short pickBrowser() {
        int r = RANDOM.nextInt(10);
        return (short) (r < 7 ? 1 : (r < 9 ? 2 : 3));
    }

    private String pickSuite(int batchIndex) {
        return switch (batchIndex % 5) {
            case 0 -> "冒烟套件";
            case 1 -> "回归套件";
            case 2 -> "P0 核心链路";
            case 3 -> "支付与订单";
            default -> "全量回归";
        };
    }

    private String pickAuthor() {
        String[] authors = {"kaneshiro", "sato", "tanaka"};
        return authors[RANDOM.nextInt(authors.length)];
    }

    private String pickAction(int seq) {
        String[] actions = {"OPEN_URL", "INPUT", "CLICK", "ASSERT_TEXT", "ASSERT_VISIBLE", "WAIT_FOR"};
        return seq == 1 ? "OPEN_URL" : actions[RANDOM.nextInt(actions.length)];
    }

    /** 失败原因取自真实会遇到的那几类，不是「Error」这种没信息量的占位 */
    private String pickError() {
        String[] errors = {
                "TimeoutError: 等待元素可点击超时（30s）—— 定位器可能已失效",
                "AssertionError: 期望文本「注文が完了しました」，实际「在庫が不足しています」",
                "TimeoutError: 页面加载超时，目标环境响应缓慢",
                "ElementNotFoundError: 定位器未匹配到任何元素",
                "AssertionError: 元素应当不存在，但仍可见",
        };
        return errors[RANDOM.nextInt(errors.length)];
    }

    private record CaseRef(String caseId, String caseCode, String title) {
    }
}
