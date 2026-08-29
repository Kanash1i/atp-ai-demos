package com.atp.platform.service;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("执行任务不存在：" + taskId);
    }
}
