package com.clitoolbox.ilink.service;

/**
 * 微信 iLink 服务接口。
 *
 * <p>上层命令只依赖该接口，不感知具体 SDK 客户端、会话文件和消息轮询实现。
 */
public interface ILinkService {

    /**
     * 发起扫码登录，并在登录成功后开始监听微信消息。
     */
    void login();

    /**
     * 恢复已有会话并监听微信消息；没有可用会话时自动进入登录流程。
     */
    void listen();
}
