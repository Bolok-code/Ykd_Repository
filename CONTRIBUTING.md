# YKD Bot 协同开发手册

## 项目概览

基于 Spring Boot + Spring AI 的微信智能机器人，通过 iLink SDK 连接微信。用户发消息 → LLM 处理（可调工具）→ 微信回复。

**技术栈**：Java 21, Spring Boot 4.x, Spring AI 2.0, DeepSeek, ElevenLabs, 高德地图

## 快速启动

```bash
# 1. 配置密钥
cp config/application-local.yml.example config/application-local.yml
# 填入真实 API Key

# 2. 启动
./mvnw spring-boot:run

# 3. 扫码登录
# 控制台输出 QR 码 URL，浏览器打开扫码
```

## 包结构

```
ykd/
  YkdApplication.java           # 启动入口
  wxbot/                        # 微信接入层
    WeixinBotService.java       # iLink 客户端生命周期、消息轮询
    BotController.java          # REST API（登录/断开/状态）
  processor/                    # 消息处理管线
    MessageProcessor.java       # 编排器：提取 → 路由 → AI → 回复
    PerUserTaskDispatcher.java  # 每用户串行队列（8线程，100容量）
    ProcessResult.java          # 结果模型：TEXT/IMAGE/VIDEO/VOICE
    UserContext.java            # ThreadLocal userId 传递
  llm/                          # AI 对话层
    config/ChatClientConfig.java   # ChatClient bean + 系统提示词
    service/LlmService.java        # 接口：chat(text, imageUrl, client, userId)
    service/impl/LlmServiceImpl    # 实现：加载记忆 → 调 LLM → 保存记忆
    tools/                         # @Tool 工具（LLM 可调用的能力）
      WeatherTools.java        # 查天气
      ImageTools.java          # 生成图片
      VideoTools.java          # 生成视频
      VoiceTools.java          # TTS 语音合成
      LinkTools.java           # 读链接
      ReminderTools.java       # 定时提醒
  task/                         # 后台异步任务
    VideoTaskManager.java       # HTTP 轮询外部视频 API（5s/次）
    ReminderTaskManager.java    # ScheduledExecutorService 本地定时器
  memory/                       # 对话记忆
    MemoryConfig.java           # 滑动窗口 40 条
    MemoryManagerService.java   # 按 userId CRUD
  weather/                      # 天气模块
    api/WeatherController.java  # REST API
    service/WeatherService.java # 接口
    service/impl/               # 高德 API 调用
    api/dto/                    # WeatherResponse, ForecastDay
  exception/                    # 统一异常处理
    ErrorCode.java              # 错误码枚举
    BusinessException.java      # 业务异常
    GlobalExceptionHandler.java # REST 异常处理 + toUserMessage()
```

## 核心数据流

```
微信用户消息
  → WeixinBotService.runBot() 轮询
  → PerUserTaskDispatcher.submit(userId, task)
  → MessageProcessor.process()
      1. 提取文本/图片/语音
      2. 路由：有图片 → agnesClient，纯文本 → deepseekClient
      3. LlmServiceImpl.chat() → ChatClient + tools + history
      4. 后处理：voiceQueue → 图片URL → 文本
  → WeixinBotService.sendResult() 发送
```

## 添加新功能

### 场景一：加一个 LLM 工具（如查股票）

1. **`llm/tools/StockTools.java`** — `@Component` + `@Tool` 方法

```java
@Slf4j
@Component
public class StockTools {
    @Tool(description = "查询股票价格。当用户询问股票时调用")
    public String getStock(@ToolParam(description = "股票代码") String code) {
        String userId = userContext.getCurrentUserId();
        try {
            // 调 API
            return "xxx 当前价格 100 元";
        } catch (Exception e) {
            log.error("[StockTools] 查询失败: userId={}, code={}", userId, code, e);
            return "❌ " + ErrorCode.XXX.getDefaultMessage();
        }
    }
}
```

2. **`ErrorCode.java`** — 加错误码
3. **`LlmServiceImpl.java`** — 构造器注入 + `.tools(..., stockTools)`
4. **`ChatClientConfig.java`** — 系统提示词加一行规则

**关键约定**：
- 用 `userContext.getCurrentUserId()` 获取用户
- try-catch + `log.error` + 返回 `"❌ " + ErrorCode`
- 返回 String（LLM 会直接展示给用户）

### 场景二：加一个异步任务（如定时任务）

参考 `ReminderTaskManager`：
- 实现 `Consumer<ProcessResult>` 回调模式，不直接依赖 `ILinkClient`
- 在 `MessageProcessor.init()` 注册回调入队
- `WeixinBotService` 主循环 poll 结果发送

### 场景三：加一个 REST 接口

在 `wxbot/BotController` 或新建 Controller，`@RestControllerAdvice` 自动覆盖异常处理。

## 代码约定

### 日志

```java
@Slf4j                    // 全部用 Lombok，禁止 LoggerFactory
log.info("[模块] 描述: key={}", value);
log.warn("[模块] 异常: ...", e);
log.error("[模块] 失败: ...", e);
```

日志前缀用 `[模块名]`，如 `[Processor]`、`[Reminder]`、`[WeatherTool]`。

### 异常处理

```java
// 工具层：catch → log → 返回友好提示
try {
    return doSomething();
} catch (Exception e) {
    log.error("[XxxTool] 失败: ...", e);
    return "❌ " + ErrorCode.XXX.getDefaultMessage();
}

// 业务层：抛 BusinessException
throw new BusinessException(ErrorCode.XXX, "具体原因");
```

### 异步模式

两种异步方式，按场景选择：

| 场景 | 方案 | 示例 |
|------|------|------|
| 外部 API 异步 | 守护线程轮询 | `VideoTaskManager`（5s 轮询 API 状态） |
| 本地定时 | `ScheduledExecutorService` | `ReminderTaskManager`（JDK 原生 schedule） |

共同约定：
- 用 `Consumer<ProcessResult>` 回调 + 队列解耦
- 不直接持有 `ILinkClient`
- 回调在 `MessageProcessor.init()` 注册

### 记录 vs 类

DTO 用 `record` + `@Builder`，其他用 `class`。

## Commit 约定

```
feat: 新增 XXX 功能
fix: 修复 XXX 问题
refactor: 重构 XXX
```

中文/英文均可，和已有历史保持一致。

## 避免合并冲突

- **`ChatClientConfig.java`** 和 **`LlmServiceImpl.java`** 是高频冲突点——加新工具时改这两个文件，尽量先拉最新代码
- 新增文件（Tool、TaskManager）几乎不会冲突
- 移动文件（重构包结构）单独提 PR，不和功能改动混在一起
- 提交前 `git pull --rebase origin main`
