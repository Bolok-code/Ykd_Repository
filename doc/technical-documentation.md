# YKD Bot 技术文档

> Spring Boot 4.1 + JDK 21 + Spring AI 2.0 微信智能机器人

---

## 1. 项目概述

YKD Bot 是一个集成微信 iLink 的 AI 智能机器人，用户通过微信扫码即可使用：

- **AI 对话** — DeepSeek + Agnes Flash 双模型驱动
- **图片生成** — Agnes Image 文生图
- **图片理解** — 发送图片，AI 识别描述
- **语音播报** — ElevenLabs TTS，文字转语音回复
- **视频生成** — 异步视频生成，后台完成后推送
- **天气查询** — 高德 API 实时/预报天气
- **对话记忆** — 40 条滑动窗口上下文，按用户隔离
- **Web 管理** — 浏览器打开 `http://localhost:8080` 扫码/配置密钥
- **Session 恢复** — 重启无需重新扫码，自动恢复登录

### 架构总览

```
浏览器 (Web 面板)
    │ POST /api/config/keys → RuntimeConfig
    │ GET /api/bot/login    → QR 码
    │
微信客户端
    │
    ▼
┌──────────────────────────────────────────────────────────┐
│  WeixinBotService (iLink SDK)                            │
│  ├── Session 持久化 (work/ilink-session.json)             │
│  ├── PerUserTaskDispatcher (每用户串行, 跨用户并行)        │
│  └── sendCompletedVideo() 异步视频推送                    │
└────────────┬─────────────────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────────────────┐
│  MessageProcessor                                        │
│  ├── extractText / extractImageDataUri / extractVoiceText │
│  ├── 路由: 有图→Agnes, 纯文本→DeepSeek                    │
│  └── UserContext (ThreadLocal userId 传递)                │
└────────────┬─────────────────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────────────────┐
│  LlmServiceImpl                                          │
│  ├── MemoryManagerService.getHistory() 加载上下文          │
│  ├── ChatClient.prompt().tools(4 个 @Tool).call()         │
│  └── MemoryManagerService.save() 保存对话                  │
└────────┬─────────────────────────────────────────────────┘
         │  LLM 自主决定调用工具
         ├── WeatherTools → WeatherService → 高德 API
         ├── ImageTools  → OpenAiImageModel → Agnes Image
         ├── VoiceTools  → ElevenLabs TTS → voiceQueue
         └── VideoTools  → VideoService → VideoTaskManager (异步)
```

---

## 2. 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 4.1.0 |
| JDK | Java | 21 |
| 构建 | Maven Wrapper | 3.9+ |
| AI 框架 | Spring AI | 2.0.0 |
| 文本模型 | DeepSeek | deepseek-chat |
| 多模态模型 | Agnes Flash | agnes-2.0-flash |
| 图片生成 | Agnes Image | agnes-image-2.1-flash |
| 语音合成 | ElevenLabs TTS | eleven_turbo_v2_5 |
| 语音识别 | ElevenLabs STT | scribe_v1 |
| 微信 SDK | wechat-ilink-sdk | 2.3.3 |
| 天气 API | 高德开放平台 | v3 |

---

## 3. 项目结构

```
ykd-repository/
├── pom.xml
├── config/
│   └── application-local.yml.example    # 本地密钥模板
└── src/main/
    ├── java/ykd/ykd/
    │   ├── YkdApplication.java          # 启动类
    │   ├── BotController.java           # Web 管理 REST API
    │   │
    │   ├── llm/
    │   │   ├── config/
    │   │   │   └── ChatClientConfig.java    # ChatClient Bean 配置
    │   │   ├── service/
    │   │   │   ├── LlmService.java          # LLM 服务接口
    │   │   │   ├── VoiceService.java        # STT (SILK→PCM→WAV→ElevenLabs)
    │   │   │   ├── VideoService.java        # 异步视频生成
    │   │   │   └── impl/
    │   │   │       └── LlmServiceImpl.java   # LLM 服务实现
    │   │   └── tools/
    │   │       ├── WeatherTools.java        # @Tool 天气查询
    │   │       ├── ImageTools.java           # @Tool 图片生成
    │   │       ├── VoiceTools.java           # @Tool 语音合成
    │   │       └── VideoTools.java           # @Tool 视频生成（异步）
    │   │
    │   ├── memory/
    │   │   ├── MemoryConfig.java            # ChatMemory Bean (40条窗口)
    │   │   └── MemoryManagerService.java    # 对话记忆 CRUD
    │   │
    │   ├── processor/
    │   │   ├── MessageProcessor.java        # 消息提取/路由/编排
    │   │   ├── ProcessResult.java           # TEXT/IMAGE/VOICE/VIDEO 结果
    │   │   ├── UserContext.java             # ThreadLocal userId
    │   │   ├── VideoTaskManager.java        # 异步视频轮询
    │   │   └── PerUserTaskDispatcher.java   # 并发任务调度
    │   │
    │   ├── weather/
    │   │   ├── api/
    │   │   │   ├── WeatherController.java   # REST: /weather/search
    │   │   │   └── dto/                     # Request/Response DTO
    │   │   ├── config/RestTemplateConfig.java
    │   │   └── service/WeatherService.java
    │   │
    │   ├── wxbot/
    │   │   └── WeixinBotService.java        # iLink 生命周期 + 消息循环
    │   │
    │   ├── config/
    │   │   └── RuntimeConfig.java           # 运行时密钥存储
    │   │
    │   └── exception/
    │       ├── ErrorCode.java
    │       ├── BusinessException.java
    │       └── GlobalExceptionHandler.java
    │
    └── resources/
        ├── application.yml                 # 主配置（占位符）
        ├── static/index.html               # Web 管理面板
        └── native/                         # SILK 编解码器
```

---

## 4. 模块详解

### 4.1 微信机器人 (wxbot)

**WeixinBotService** — iLink 客户端生命周期管理

#### 启动流程

```
@PostConstruct start()
  └─ 守护线程 wx-bot → runBot()
      ├─ loadSession() → 有则恢复，无需扫码
      ├─ login() → 无保存 Session → 打印 QR 码
      ├─ client.getLoginFuture().get() → 等待扫码
      ├─ saveSession() → 持久化到 work/ilink-session.json
      └─ while(running):
          ├─ client.getUpdates() → 消息列表
          ├─ saveSession() → 每条消息后保存 cursor
          ├─ handleMessage(msg) → PerUserTaskDispatcher.submit()
          └─ sendCompletedVideo() → 异步视频推送
```

#### Session 持久化

`work/ilink-session.json` 保存 botToken、userId、botId、baseUrl、updatesCursor。重启后自动恢复，无需重新扫码。该文件在 .gitignore 中。

#### PerUserTaskDispatcher

- 每用户串行（同用户消息按序处理）
- 跨用户并行（不同用户同时处理）
- 配置：8 线程，100 总容量，5 条/用户
- 队列满时拒绝新消息并记日志

---

### 4.2 消息处理 (processor)

**MessageProcessor** — 消息提取、模型路由、结果编排

```
process(msg, client)
  ├── extractVoiceText(msg)    → iLink 语音转文字
  ├── extractText(msg)         → MessageItem.text_item
  ├── extractImageDataUri(msg) → CDNMedia → base64 data URI
  │
  ├── 路由: hasImage ? agnesClient : deepseekClient
  ├── UserContext.executeAs(userId, () -> {
  │       reply = llmService.chat(text, imageUri, client, userId)
  │       voiceResult = voiceQueue.poll()  → 优先级最高
  │       imageUrl = extractUrl(reply)     → 优先级第二
  │       fallback → ProcessResult.text()
  │   })
  └── return ProcessResult
```

**ProcessResult** — 统一结果封装

```java
public record ProcessResult(Type type, String text, byte[] data, String userId) {
    enum Type { TEXT, IMAGE, VIDEO, VOICE }
}
```

**UserContext** — ThreadLocal 传递 userId 给 @Tool 方法

**VideoTaskManager** — 守护线程每 5 秒轮询待完成的视频任务，完成后通过回调入队。

---

### 4.3 LLM 服务 (llm)

#### ChatClientConfig

两个 ChatClient Bean，注入不同的 System Prompt：

| Bean | 模型 | 用途 |
|------|------|------|
| `deepseekClient` | DeepSeekChatModel | 文本对话 + 工具调用 |
| `agnesClient` | OpenAiChatModel → Agnes Flash | 多模态图片理解 |

#### LlmServiceImpl

```java
public String chat(String text, String imageUrl, ChatClient client, String userId) {
    List<Message> history = memoryManagerService.getHistory(userId);
    String content = client.prompt()
        .messages(history)
        .user(userSpec -> {
            userSpec.text(text);
            if (imageUrl != null) userSpec.media(...);
        })
        .tools(weatherTools, imageTools, videoTools, voiceTools)
        .call().content();
    memoryManagerService.save(userId, text, content);
    return content;
}
```

#### @Tool 工具

| 工具 | 触发条件 | 实现 |
|------|----------|------|
| `WeatherTools.getWeather(city)` | 用户问天气 | 高德 API 实时天气 |
| `ImageTools.generateImage(prompt, refUrl)` | 要求画图/生成图片 | Agnes Image API 1024×1024 |
| `VoiceTools.speak(text)` | 要求朗读/语音回复 | ElevenLabs TTS → voiceQueue |
| `VideoTools.generateVideo(prompt)` | 要求生成视频 | Agnes Video 异步 → VideoTaskManager |

LLM 根据用户消息自行判断是否调用工具，开发者只需注册 `@Tool` 方法，无需编写意图识别代码。

---

### 4.4 对话记忆 (memory)

**MemoryConfig** — `MessageWindowChatMemory` 40 条消息滑动窗口，`InMemoryChatMemoryRepository` 存储（重启丢失）。

**MemoryManagerService** — 封装 `ChatMemory`，提供 `getHistory(userId)` / `save(userId, text, reply)` / `clear(userId)` 方法。在 `LlmServiceImpl` 中自动加载和保存。

---

### 4.5 语音服务

| 服务 | 方向 | 流程 |
|------|------|------|
| **VoiceTools.speak()** | 输出 (TTS) | 文本 → ElevenLabs `eleven_turbo_v2_5` → MP3 → voiceQueue → WeixinBotService 发送文件 |
| **VoiceService.speechToText()** | 输入 (STT) | 已弃用，iLink SDK 自带语音转文字 |

---

### 4.6 Web 管理面板

`http://localhost:8080` 提供：

- **密钥配置** — 输入 DeepSeek / Agnes / ElevenLabs 密钥并保存到 RuntimeConfig
- **扫码登录** — 生成 QR 码，微信扫码登录
- **连接状态** — 实时显示是否在线
- **断开连接** — 一键断开并清理 Session

#### REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/config/keys` | 保存 API 密钥 |
| GET | `/api/bot/login` | 触发登录，返回 QR URL |
| POST | `/api/bot/disconnect` | 断开连接 |
| GET | `/api/bot/status` | 查询连接状态 |
| GET | `/weather/search` | 天气查询（city + type 参数） |

---

### 4.7 异常处理 (exception)

| 类 | 职责 |
|----|------|
| `ErrorCode` | 枚举：API_ERROR, CITY_NOT_FOUND, NETWORK_ERROR, AI_CALL_FAILED 等 |
| `BusinessException` | 携带 ErrorCode 的运行时异常 |
| `GlobalExceptionHandler` | `@RestControllerAdvice` 拦截异常，返回 JSON 错误体 |

---

## 5. 核心调用链路

### 文本聊天

```
微信 "你好"
  → WeixinBotService.handleMessage()
  → PerUserTaskDispatcher.submit(userId)
  → MessageProcessor.process()
    → UserContext.executeAs(userId)
    → LlmServiceImpl.chat("你好", null, deepseekClient, userId)
      → MemoryManagerService.getHistory(userId)  // 加载上下文
      → ChatClient.prompt().messages(history).tools(...).call()
      → DeepSeek 返回文本
      → MemoryManagerService.save(userId, "你好", reply)  // 保存上下文
    → ProcessResult.text(reply)
  → WeixinBotService.sendResult() → safeSendText()
```

### 天气查询

```
微信 "北京天气"
  → ... (同上路由)
  → LlmServiceImpl.chat("北京天气", null, deepseekClient, userId)
    → LLM 识别意图 → 调用 WeatherTools.getWeather("北京")
      → WeatherServiceImpl.getWeatherText("北京")
        → resolveCityCode("北京") → adcode "110000"
        → GET 高德天气 API → 解析 JSON
        → "晴 26°C 南风 湿度60%"
    → LLM 将天气数据转为自然语言回复
  → safeSendText()
```

### 语音播报

```
微信 "朗读一段话"
  → ... (同上路由)
  → LlmServiceImpl.chat("朗读一段话", null, deepseekClient, userId)
    → LLM → 调用 VoiceTools.speak("朗读内容")
      → ElevenLabs TTS → MP3 字节
      → voiceQueue.add(ProcessResult.voice(audio, userId))
    → LLM 返回 "语音已播报"
  → MessageProcessor 检查 voiceQueue.poll() → 有语音结果
  → ProcessResult.voice → safeSendVoice()
```

---

## 6. 配置说明

### 密钥管理

使用 `application-local.yml` 存放真实密钥，不提交到 Git：

```bash
cp config/application-local.yml.example config/application-local.yml
# 编辑 config/application-local.yml，填入真实密钥
```

`application.yml` 中只保留占位符 `changeme` 和公用的 base-url 配置。Spring Boot 通过 `spring.config.import` 自动加载 `config/application-local.yml` 覆盖默认值。

### application.yml 结构

```yaml
spring:
  config.import: optional:file:./config/application-local.yml
  ai:
    chat.client.enabled: false      # 关闭自动 ChatClient
    openai:                          # Agnes AI
      base-url: https://apihub.agnes-ai.com/v1
      chat.options.model: agnes-2.0-flash
    deepseek:                        # DeepSeek
      base-url: https://api.deepseek.com
      chat.options.model: deepseek-chat
    elevenlabs:                      # ElevenLabs TTS
      base-url: https://api.elevenlabs.io
      tts.voice-id: EXAVITQu4vr4xnSDxMaL
      tts.model: eleven_turbo_v2_5
gaode:
  key: changeme
```

### 关键配置项

| 配置 | 说明 |
|------|------|
| `spring.ai.chat.client.enabled=false` | 禁用自动 ChatClient，手动创建 deepseekClient / agnesClient |
| `spring.config.import` | 自动加载本地密钥文件 |
| `spring.ai.openai.*` | OpenAI 兼容接口（Agnes AI），ChatClient + ImageModel 共用 |

---

## 7. 数据流图

```
用户发送消息
    │
    ▼
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│ WeixinBot   │────▶│ MessageProcessor  │────▶│ LlmServiceImpl   │
│ Service     │     │                  │     │                 │
│ (iLink SDK) │     │ extractText()    │     │ getHistory()    │
│             │     │ extractImage()   │     │ prompt().tools() │
│ session     │     │ extractVoice()   │     │ save()          │
│ persistence │     │ route()          │     │                 │
└─────────────┘     └────────┬─────────┘     └────────┬────────┘
                             │                        │
                             │ UserContext            │ @Tool callbacks
                             │ ThreadLocal            │
                             ▼                        ▼
                    ┌──────────────────┐    ┌────────────────────┐
                    │ ProcessResult    │    │ WeatherTools       │
                    │                  │    │ ImageTools         │
                    │ TEXT / IMAGE     │    │ VoiceTools         │
                    │ / VOICE / VIDEO  │    │ VideoTools         │
                    └──────────────────┘    └────────────────────┘
```

---

> **文档版本**: v2.0  
> **最后更新**: 2026-07-21
