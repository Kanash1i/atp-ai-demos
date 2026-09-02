import { existsSync, chmodSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { arch, platform } from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));

/**
 * 挑出当前平台的二进制。
 *
 * ⚠️ 包里带的是全部平台的产物，不是 postinstall 去下载。
 *    下载方案要么依赖公网 release、要么依赖仓库权限，而这个包的使用场景
 *    是「把 CLI 装到测试人员的机器上」—— 那些机器的网络环境无法假设。
 *    多带 30MB 换掉一整类安装期故障，划算。
 */
function resolveBinary() {
  const os = platform(); // linux | darwin | win32
  const cpu = arch(); // x64 | arm64
  const goos = os === "win32" ? "windows" : os;
  const goarch = cpu === "x64" ? "amd64" : cpu;
  const ext = goos === "windows" ? ".exe" : "";
  const p = join(HERE, "bin", `atp-${goos}-${goarch}${ext}`);

  if (!existsSync(p)) {
    throw new Error(
      `atp CLI 不支持当前平台：${goos}/${goarch}。` +
        `包内提供的是 linux、darwin 的 amd64/arm64 与 windows/amd64。`,
    );
  }
  // npm 打包会丢掉可执行位，装完必须自己补
  if (goos !== "windows") {
    try {
      chmodSync(p, 0o755);
    } catch {
      /* 只读安装目录（比如 pnpm store）下失败是正常的，交给调用时报错 */
    }
  }
  return p;
}

/**
 * atp CLI 的 opencode 插件。
 *
 * <h3>它做三件事</h3>
 *
 * 1. 把包内的 atp 二进制路径告诉模型（每台机器的路径都不一样，不能写死在 skill 里）
 * 2. 注入权限规则 —— ⭐ **`commit` 是唯一需要人点确认的命令**
 * 3. 把案例编写的 skill 挂进 instructions
 *
 * <h3>⚠️ 为什么权限规则要在这里生成，而不是让用户抄进 opencode.json</h3>
 *
 * 规则里带着二进制的绝对路径，而那个路径取决于包装在哪、用的什么包管理器。
 * 让用户手抄一次就等于给了他一次抄错的机会 —— 而抄错的后果不是报错，
 * 是 `commit` 的规则匹配不上，于是落到兜底的 `"*": "ask"`（表面正常）
 * 或者更糟：某条 allow 规则误匹配，**提交不再需要人确认，而没有任何提示**。
 */
export const AtpCliPlugin = async ({ $ }) => {
  const bin = resolveBinary();

  return {
    config: async (config) => {
      config.permission ??= {};
      config.permission.bash ??= {};

      // ⚠️ 顺序有意义：opencode 按最长前缀匹配，commit 那条必须比通配的 allow 更具体。
      //    这里全部用绝对路径，避免与用户自己的 atp 命令混淆。
      const rules = {
        [`${bin} schema*`]: "allow",
        [`${bin} modules*`]: "allow",
        [`${bin} validate*`]: "allow",
        [`${bin} show*`]: "allow",
        [`${bin} preview*`]: "allow",
        [`${bin} draft*`]: "allow",
        [`${bin} update*`]: "allow",
        [`${bin} inspect*`]: "allow",
        [`${bin} run*`]: "allow",

        // ⭐ 保守路线的核心：写库的最后一步必须由人点确认。
        //    这一条不是提示词里的一句话，是模型执行不了的一道门。
        [`${bin} commit*`]: "ask",
      };

      // 用户已有的规则优先 —— 插件不该覆盖人的显式配置
      config.permission.bash = { ...rules, ...config.permission.bash };

      config.instructions ??= [];
      const skill = join(HERE, "skills", "atp-case-authoring", "SKILL.md");
      if (!config.instructions.includes(skill)) config.instructions.push(skill);
    },

    tool: {
      /**
       * 唯一注册的工具：告诉模型二进制在哪、当前环境通不通。
       *
       * 其余命令刻意**不**包装成工具，仍然走 bash —— 那样权限门才看得见完整命令，
       * 人在确认 `commit` 时读到的是将要执行的那一行，而不是一个工具名加一堆参数。
       */
      atp_env: {
        description:
          "查出 atp CLI 的可执行路径并确认它能跑。开始编写测试案例前先调一次，" +
          "拿到的路径用于后续所有 atp 命令（每台机器不同，不要猜）。",
        args: {},
        async execute() {
          let version = "";
          let ok = true;
          try {
            const r = await $`${bin} --version`.quiet().nothrow();
            version = (r.stdout?.toString() || r.stderr?.toString() || "").trim();
            ok = r.exitCode === 0;
          } catch (e) {
            ok = false;
            version = String(e?.message ?? e);
          }
          return [
            `atp 可执行路径：${bin}`,
            `自检：${ok ? "通过" : "失败"} ${version}`,
            ``,
            `后续命令一律用这个绝对路径，例如：`,
            `  ${bin} modules --json`,
            `  ${bin} schema`,
            ``,
            `⚠️ commit 需要人确认，其余命令已放行。`,
            `⚠️ 数据库连接从工作目录的 .env 读（ATP_DB_URL / ATP_DB_USER / ATP_DB_PASSWORD）。`,
          ].join("\n");
        },
      },
    },
  };
};

export default { id: "atp-cli", server: AtpCliPlugin };
