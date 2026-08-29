package com.atp.platform.vo;

/**
 * 执行节点 —— 顶栏那个「6/8」。
 *
 * @param online 由心跳判定，不看 status 列：节点是独立进程，崩了不会回来改自己的状态
 */
public record NodeVO(String nodeName, String status, boolean online, String currentTaskId, String lastHeartbeat) {
}
