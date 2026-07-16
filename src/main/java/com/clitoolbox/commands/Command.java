package com.clitoolbox.commands;

/**
 * 所有命令都必须实现的接口
 */
public interface Command {
    void run(String[] args);
}
