import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  api, ApiError, commitDraft, createDraft, decideApproval, dispatchRun, saveDraft,
  DEMO_USER, NO_CONTENT,
} from './api';
import type { CreateDraftRequest, Decision, DispatchRequest } from './types';

/** 后端不在的时候不要重试三次再报错 —— 演示现场要立刻看到「连不上」 */
const once = { retry: 0 } as const;

export const isNoContent = (v: unknown): boolean => v === NO_CONTENT;

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
 *
 * 轮询 2 秒一次（契约建议值）。**拿到 204 就停止轮询**，同时刷新 /stats 与 /recent ——
 * 批次跑完那一刻，统计和最近执行才有新数据。
 */
export function useRunningBatch() {
  const qc = useQueryClient();
  const settled = useRef(false);

  const query = useQuery({
    queryKey: ['exec', 'running'],
    queryFn: api.execRunning,
    refetchInterval: (q) => (isNoContent(q.state.data) ? false : 2000),
    ...once,
  });

  useEffect(() => {
    if (query.data === undefined) return;

    if (isNoContent(query.data)) {
      // 只在「有批次 → 没批次」这一次跳变时刷新，避免空闲时每次轮询都打两个接口
      if (settled.current) {
        settled.current = false;
        void qc.invalidateQueries({ queryKey: ['exec', 'stats'] });
        void qc.invalidateQueries({ queryKey: ['exec', 'recent'] });
        void qc.invalidateQueries({ queryKey: ['exec', 'nodes'] });
      }
      return;
    }

    settled.current = true;
  }, [query.data, qc]);

  return query;
}

/**
 * 派发执行。成功后立刻把 /running 拉一次，让「执行中的批次」马上活起来，
 * 不用等下一个轮询周期。
 */
export function useDispatch() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: DispatchRequest) => dispatchRun(body),
    retry: 0,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['exec', 'running'] });
    },
  });
}

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

/* ---------- 写侧（面板⑥）---------- */

/** 模块字典。12 条固定数据，缓存久一点 */
export const useModules = () =>
  useQuery({ queryKey: ['modules'], queryFn: api.modules, staleTime: 10 * 60_000, ...once });

/** 取号是有副作用语义的（并发下会重号），所以按需调用，不做缓存 */
export function useNextCaseCode() {
  return useMutation({ mutationFn: (moduleId: string) => api.nextCaseCode(moduleId), retry: 0 });
}

export const useDraft = (caseId: string | null) =>
  useQuery({
    queryKey: ['draft', caseId],
    queryFn: () => api.draft(caseId!),
    enabled: Boolean(caseId),
    ...once,
  });

export function useCreateDraft() {
  return useMutation({ mutationFn: (body: CreateDraftRequest) => createDraft(body), retry: 0 });
}

/**
 * 保存草稿。**不重试** —— 409 意味着别人改过了，重试会覆盖他的改动。
 * 成功后把树和这条案例的缓存都作废：标题、优先级可能变了。
 */
export function useSaveDraft() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { caseId: string; draftJson: string; version: number }) =>
      saveDraft(v.caseId, v.draftJson, v.version),
    retry: 0,
    onSuccess: (_d, v) => {
      void qc.invalidateQueries({ queryKey: ['draft', v.caseId] });
    },
  });
}

/** 提交落地。成功后树里会多出这条案例，所以整棵树和案例详情都要作废 */
export function useCommitDraft() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { caseId: string; version: number }) => commitDraft(v.caseId, v.version),
    retry: 0,
    onSuccess: (_d, v) => {
      void qc.invalidateQueries({ queryKey: ['tree'] });
      void qc.invalidateQueries({ queryKey: ['case', v.caseId] });
      void qc.invalidateQueries({ queryKey: ['draft', v.caseId] });
    },
  });
}

export { ApiError };
