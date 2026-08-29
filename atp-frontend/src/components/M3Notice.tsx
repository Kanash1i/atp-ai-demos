/** M3 才有后端的面板顶部挂这条 —— 演示时说清楚哪些是真的、哪些还是稿子 */
export default function M3Notice({ text }: { text: string }) {
  return (
    <div className="mb-[18px] flex items-start gap-2.5 rounded-md border border-dashed border-line-2 bg-surface-2 px-4 py-3">
      <span className="mt-px shrink-0 rounded-xs bg-ink px-[7px] py-[3px] font-mono text-[9.5px] tracking-[.12em] text-paper">
        M3
      </span>
      <span className="text-[11.5px] leading-[1.8] text-ink-2">{text}</span>
    </div>
  );
}
