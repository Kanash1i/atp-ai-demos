import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, decideApproval, DEMO_USER, NO_CONTENT } from './api';
import type { Decision } from './types';

/** 后端不在的时候不要重试三次再报错 —— 演示现场要立刻看到「连不上」 */
const once = { retry: 0 } as const;

export const useProjects = () =>
  useQuery({ queryKey: ['projects'], queryFn: api.projects, ...once });

export const useProjectTree = (projectId: string | undefined) =>
  useQuery({
    queryKey: ['tree', projectId],
    queryFn: () => api.projectTree(projectId!),
    enabled: Boolean(projectId),
    ...once,
  });

export const useCaseDetail = (caseId: string | undefined) =>
  useQuery({
    queryKey: ['case', caseId],
    queryFn: () => api.caseDetail(caseId!),
    enabled: Boolean(caseId),
    ...once,
  });

export const useExecStats = () =>
  useQuery({ queryKey: ['exec', 'stats'], queryFn: api.execStats, ...once });

/**
 * 执行中的批次。没有批次在跑时后端返回 204，这里 data 是 NO_CONTENT。
 * 摆一个不动的假进度条演示时一刷新就露馅，所以空状态是正经状态，不是错误。
 */
export const useRunningBatch = () =>
  useQuery({
    queryKey: ['exec', 'running'],
    queryFn: api.execRunning,
    refetchInterval: 5000,
    ...once,
  });

export const isNoContent = (v: unknown): boolean => v === NO_CONTENT;

export const useRecentRuns = (limit = 200) =>
  useQuery({ queryKey: ['exec', 'recent', limit], queryFn: () => api.execRecent(limit), ...once });

export const useTaskDetail = (taskId: string | null) =>
  useQuery({
    queryKey: ['exec', 'task', taskId],
    queryFn: () => api.execTask(taskId!),
    enabled: Boolean(taskId),
    ...once,
  });

export const useNodes = () =>
  useQuery({ queryKey: ['exec', 'nodes'], queryFn: api.execNodes, refetchInterval: 15000, ...once });

export const useApprovalStats = () =>
  useQuery({ queryKey: ['approvals', 'stats', DEMO_USER], queryFn: () => api.approvalStats(), ...once });

export const usePendingApprovals = (assignee?: string) =>
  useQuery({
    queryKey: ['approvals', 'pending', assignee ?? null],
    queryFn: () => api.approvalsPending(assignee),
    ...once,
  });

export const useMyApprovals = () =>
  useQuery({ queryKey: ['approvals', 'mine', DEMO_USER], queryFn: () => api.approvalsMine(), ...once });

/**
 * 决策。409 = 已被别人处理过（并发仲裁）—— 提示「刷新看看」，**不要自动重试**。
 * 成功后把审批相关的缓存全部作废，计数与列表一起更新。
 */
export function useDecide() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { requestId: string; decision: Decision; note: string }) =>
      decideApproval(vars.requestId, { decision: vars.decision, note: vars.note }),
    retry: 0,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}

export { ApiError };
