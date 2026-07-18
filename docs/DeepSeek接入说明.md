# Spring Boot + Spring AI + DeepSeek 接入说明

## 1. 架构

```text
手机微信
  -> OpenILink SDK
  -> Spring Boot
  -> CliApplicationRunner / ChatCommand
  -> ILinkService（微信能力接口）
  -> ILinkServiceImpl（登录、收消息、发消息与会话恢复）
  -> PerUserTaskDispatcher（同用户串行、不同用户并行）
  -> ChatService（命令、多轮历史）
  -> AiChatClient
  -> SpringAiDeepSeekClient
  -> Spring AI OpenAiChatModel
  -> DeepSeek Chat Completions API
```

各层职责：

- `App`：Spring Boot 启动入口，当前以非 Web 的 CLI 模式运行。
- `CliApplicationRunner`：读取命令行参数并从 Spring 容器取得对应命令 Bean。
- `ILinkService`：定义微信登录和消息监听能力，上层命令只依赖接口。
- `ILinkServiceImpl`：封装具体 OpenILink SDK 客户端、会话恢复、消息拉取和回复。
- `PerUserTaskDispatcher`：避免 DeepSeek 慢请求阻塞消息拉取，并保证同一用户的回复顺序。
- `ChatService`：维护每个微信用户独立的对话历史，处理 `/clear` 等命令。
- `AiChatClient`：隔离具体大模型供应商。
- `SpringAiDeepSeekClient`：把项目消息转换为 Spring AI 消息，调用
  `OpenAiChatModel`，并把 Spring AI 异常转换为项目错误。
- `DeepSeekAiConfiguration`：集中创建模型、聊天客户端、会话仓库和任务队列 Bean。

项目没有启动 HTTP 服务。手机聊天入口仍是微信，Spring Boot 在这里负责依赖注入、
配置绑定、日志和生命周期管理。

## 2. API 调用

DeepSeek 使用 OpenAI 兼容格式，Spring AI 最终调用：

```text
POST https://api.deepseek.com/chat/completions
```

请求格式：

```json
{
  "model": "deepseek-v4-flash",
  "messages": [
    {"role": "system", "content": "系统提示词"},
    {"role": "user", "content": "用户问题"}
  ],
  "thinking": {"type": "disabled"},
  "stream": false,
  "max_tokens": 1000
}
```

回答从以下字段读取：

```text
choices[0].message.content
```

`OpenAiChatModel` 由项目手动配置为 Spring Bean，这样可以明确设置 DeepSeek 的
基础地址、`/chat/completions` 路径、连接/读取超时、三次有限重试，以及 DeepSeek
专用的顶层 `thinking` 参数。

官方文档：

- [DeepSeek 首次 API 调用](https://api-docs.deepseek.com/)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion)
- [Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)

## 3. Spring Boot 配置

必填：

| 变量 | 说明 |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek 控制台生成的 API Key |

可选：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | API 基础地址 |
| `DEEPSEEK_MODEL` | `deepseek-v4-flash` | 模型名称 |
| `DEEPSEEK_MAX_TOKENS` | `1000` | 单次最大输出 Token |
| `DEEPSEEK_TIMEOUT_SECONDS` | `60` | 请求超时秒数 |
| `DEEPSEEK_THINKING_ENABLED` | `false` | 是否开启思考模式 |
| `DEEPSEEK_HISTORY_ROUNDS` | `10` | 每个用户保留的对话轮数 |

这些变量在 `src/main/resources/application.yml` 中映射到
`app.deepseek.*`，再绑定为 `DeepSeekProperties`。真实密钥不进入
`application.yml`、源码或 Git。

PowerShell 当前窗口临时配置示例：

```powershell
$env:DEEPSEEK_API_KEY="你的API Key"
```

需要长期使用时，请在 Windows“环境变量”界面添加用户变量
`DEEPSEEK_API_KEY`，不要把真实 Key 写入源码、`pom.xml`、`run.bat` 或 Git。

## 4. 运行

使用 JDK 21 打包 Spring Boot 可执行 Jar：

```powershell
mvn clean package
```

首次登录：

```powershell
run.bat chat login
```

以后启动：

```powershell
run.bat chat
```

微信中可以使用：

```text
/clear  清空当前对话
/new    开启新对话
/status 查看 AI 状态
/model  查看当前模型
/help   查看帮助
```

发送“北京天气”等消息时查询实时天气；发送“明天杭州天气”“杭州7月20日天气”
等消息时，会同时解析城市和日期并查询每日预报。其他文本交给 DeepSeek。

也可以通过 Maven 启动：

```powershell
mvn spring-boot:run -Dspring-boot.run.arguments="chat"
```

## 5. 会话与并发

- 对话历史以 OpenILink 的微信 `userId` 隔离。
- 历史当前只保存在内存中，程序重启后清空。
- 每个用户默认保留最近 10 轮对话。
- 单条用户消息最多 4000 个字符。
- 发送给模型的历史文本最多约 20000 个字符。
- 同一用户的消息严格按提交顺序处理。
- 不同用户最多 8 个并发处理。
- 每个用户最多保留 5 个待处理任务，线程池全局最多接受 100 个任务。

## 6. 错误处理

项目已处理：

- API Key 缺失或无效
- API 余额不足
- HTTP 429 限流
- Spring AI 网络错误与请求超时
- DeepSeek 5xx 服务异常
- 空回答
- 内容过滤
- 模型资源不足
- 输出达到长度限制

微信用户只看到友好错误提示，详细原因记录在本地日志中。

## 7. 当前可靠性边界

OpenILink cursor 在消息被投递到异步任务后立即持久化，因此当前采用
“至多一次”处理语义：如果程序在模型回答完成前异常崩溃，该条消息可能不会重放。

此外，模型回答会先写入内存历史再发送到微信。如果微信发送在此时失败，
下一轮模型仍可能看到用户未收到的上一条回答。生产版本应将“生成回答、发送状态、
提交会话历史”纳入持久化状态机。

正常退出时程序会停止接收新任务、等待已进入队列的任务，再关闭 ILink 客户端。
如果后续需要生产级“不丢消息”，应增加 SQLite inbox/outbox，
记录消息 ID、处理状态和待发送回复。

## 8. 主要项目结构

```text
src/main/java/com/clitoolbox/
├─ App.java                         Spring Boot 入口
├─ CliApplicationRunner.java        CLI 命令分发
├─ ai/
│  ├─ DeepSeekAiConfiguration.java  Spring Bean 配置
│  ├─ SpringAiDeepSeekClient.java   Spring AI 适配层
│  └─ AiChatClient.java             模型抽象
├─ config/
│  ├─ DeepSeekProperties.java       Boot 配置绑定
│  └─ DeepSeekConfig.java           已校验的运行配置
├─ conversation/                    多轮历史与并发队列
├─ ilink/service/
│  ├─ ILinkService.java             微信服务接口
│  └─ impl/ILinkServiceImpl.java    OpenILink SDK 实现与微信收发主链路
└─ weather/                         天气能力
```
