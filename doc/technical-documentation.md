# Ykd 项目技术文档

> 基于 Spring Boot 4.1.0 + JDK 21 的 AI 微信机器人项目，集成 DeepSeek / Agnes AI 大模型与高德天气 API。

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈](#2-技术栈)
3. [项目结构](#3-项目结构)
4. [模块详解](#4-模块详解)
   - [4.1 应用入口](#41-应用入口)
   - [4.2 微信机器人 (wxbot)](#42-微信机器人-wxbot)
   - [4.3 LLM 服务 (llm)](#43-llm-服务-llm)
   - [4.4 天气服务 (weather)](#44-天气服务-weather)
   - [4.5 异常处理 (exception)](#45-异常处理-exception)
5. [核心调用链路](#5-核心调用链路)
   - [5.1 文本聊天流程](#51-文本聊天流程)
   - [5.2 图片生成流程](#52-图片生成流程)
   - [5.3 天气查询流程](#53-天气查询流程)
6. [配置说明](#6-配置说明)
7. [API 接口](#7-api-接口)

---

## 1. 项目概述

Ykd 是一个集成微信聊天机器人与 AI 大模型能力的 Java 服务端应用。用户通过微信扫码登录机器人后，可以：

- **AI 对话**：与 DeepSeek 大模型进行文本对话
- **图片生成**：通过 Agnes AI 生成图片（基于文本描述）
- **图片理解**：发送图片给机器人，由 Agnes Flash 多模态模型识别
- **天气查询**：通过高德 API 查询实时天气

### 架构总览

```
微信客户端
    │
    ▼
┌──────────────────┐     ┌─────────────────────┐     ┌──────────────────┐
│  WeixinBot       │────▶│    LlmService       │────▶│  DeepSeek        │
│  (iLink SDK)     │     │  (ChatClient 编排)   │     │  (文本对话)       │
└──────────────────┘     │                     │     └──────────────────┘
        │                │  .tools() 注册工具   │     ┌──────────────────┐
        ▼                │                     │────▶│  Agnes Flash      │
┌──────────────────┐     │  ┌──────────────┐   │     │  (多模态对话)     │
│ 高德天气 API     │     │  │ WeatherTools │   │     └──────────────────┘
│  (实时/预报)      │     │  └──────┬───────┘   │     ┌──────────────────┐
└──────────────────┘     │  ┌──────────────┐   │────▶│  Agnes Image     │
                         │  │  ImageTools  │   │     │  (图片生成)       │
                         │  └──────────────┘   │     └──────────────────┘
                         └─────────────────────┘
```

---

## 2. 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 4.1.0 |
| JDK | Java | 21 |
| 构建 | Maven | 3.9.11 (Wrapper) |
| AI 框架 | Spring AI | 2.0.0 |
| 文本模型 | DeepSeek Chat | deepseek-chat |
| 多模态模型 | Agnes AI Flash | agnes-2.0-flash |
| 图片生成模型 | Agnes AI Image | agnes-image-2.1-flash |
| 微信 SDK | wechat-ilink-sdk | 2.3.3 |
| 天气 API | 高德开放平台 | v3 |
| JSON 处理 | Jackson | (Spring Boot 内置) |
| HTTP 客户端 | RestTemplate | (Spring 内置) |
| 工具类 | Lombok | (编译期注解) |

---

## 3. 项目结构

```
ykd-repository/
├── pom.xml                              # Maven 项目配置
├── mvnw / mvnw.cmd                      # Maven Wrapper
├── README.md                            # 项目说明
├── .gitignore                           # Git 忽略规则
│
└── src/
    ├── main/
    │   ├── java/ykd/ykd/
    │   │   ├── YkdApplication.java                  # Spring Boot 启动类
    │   │   │
    │   │   ├── llm/
    │   │   │   ├── config/
    │   │   │   │   └── ChatClientConfig.java         # ChatClient Bean 配置
    │   │   │   ├── service/
    │   │   │   │   ├── LlmService.java               # LLM 服务接口
    │   │   │   │   └── impl/
    │   │   │   │       └── LlmServiceImpl.java        # LLM 服务实现
    │   │   │   └── tools/
    │   │   │       ├── ImageTools.java                # 图片生成 @Tool
    │   │   │       └── WeatherTools.java              # 天气查询 @Tool
    │   │   │
    │   │   ├── weather/
    │   │   │   ├── config/
    │   │   │   │   └── RestTemplateConfig.java        # RestTemplate Bean
    │   │   │   ├── api/
    │   │   │   │   ├── WeatherController.java         # 天气 REST 接口
    │   │   │   │   └── dto/
    │   │   │   │       ├── WeatherRequest.java        # 请求 DTO
    │   │   │   │       ├── WeatherResponse.java       # 响应 DTO
    │   │   │   │       └── ForecastDay.java           # 预报 DTO
    │   │   │   └── service/
    │   │   │       ├── WeatherService.java            # 天气服务接口
    │   │   │       └── impl/
    │   │   │           └── WeatherServiceImpl.java     # 天气服务实现
    │   │   │
    │   │   ├── wxbot/
    │   │   │   └── WeixinBotService.java              # 微信机器人服务
    │   │   │
    │   │   └── exception/
    │   │       ├── ErrorCode.java                     # 错误码枚举
    │   │       ├── BusinessException.java             # 业务异常
    │   │       └── GlobalExceptionHandler.java        # 全局异常处理器
    │   │
    │   └── resources/
    │       └── application.properties                 # 应用配置
    │
    └── test/java/ykd/ykd/
        └── YkdApplicationTests.java                   # 启动上下文测试
```

---

## 4. 模块详解

### 4.1 应用入口

**文件**: `YkdApplication.java`

```java
@SpringBootApplication
public class YkdApplication {
    public static void main(String[] args) {
        SpringApplication.run(YkdApplication.class, args);
    }
}
```

标准 Spring Boot 启动类，`@SpringBootApplication` 启用自动配置和组件扫描。

---

### 4.2 微信机器人 (wxbot)

**文件**: `WeixinBotService.java`

核心类，负责微信消息的接收、路由和回复。

#### 依赖注入

```java
public WeixinBotService(LlmService llmService,
                        ChatClient deepseekClient,
                        ChatClient agnesClient)
```

注入三个 Bean：
- `LlmService` — AI 对话编排服务
- `deepseekClient` — DeepSeek 文本模型客户端
- `agnesClient` — Agnes Flash 多模态模型客户端

#### 启动生命周期

```
@PostConstruct start()
    └─ 创建守护线程 "wx-bot" → runBot()
        ├─ 构建 ILinkClient
        ├─ 调用 client.executeLogin() 获取二维码
        ├─ 等待用户扫码登录 (client.getLoginFuture().get())
        └─ 进入轮询循环:
            └─ client.getUpdates() → handleMessage(msg)

@PreDestroy stop()
    └─ running = false → 轮询退出 → client.close()
```

#### 消息处理

**`handleMessage(WeixinMessage msg)`** 是消息路由核心：

```
收到微信消息
    │
    ├── extractText(msg) → 提取文本
    ├── extractImageDataUri(msg) → 下载 CDN 图片 → base64 data URI
    │
    ├── 有图片 → 使用 agnesClient
    └── 无图片 → 使用 deepseekClient
            │
            ▼
    llmService.chat(text, imageDataUri, client)
            │
            ▼
    处理回复:
    ├── extractUrl(reply) 发现 URL → downloadImage(url) → safeSendImage()
    └── 无 URL → safeSendText(reply)
```

#### 关键方法

| 方法 | 作用 |
|------|------|
| `extractText()` | 从 `MessageItem` 列表中提取文本 |
| `extractImageDataUri()` | 通过 `CDNMedia → client.downloadMedia()` 下载图片，编码为 `data:image/jpeg;base64,...` |
| `extractUrl()` | 正则 `https?://\S+` 提取图片 URL |
| `downloadImage()` | HTTP GET 下载图片为字节数组 |
| `safeSendText()` | 异常安全的文本发送 |
| `safeSendImage()` | 异常安全的图片发送 |

#### 图片提取演进说明

> **旧方案问题**: `ImageItem.getUrl()` 方法不存在（iLink SDK 图片存储在 CDN，不暴露公网 URL），纯图片消息会被丢弃。
>
> **新方案**: 通过 `CDNMedia → client.downloadMedia()` 下载字节 → Base64 编码为 data URI 传给 AI。`URI.create()` 原生支持 `data:` 协议，无需改动 `LlmServiceImpl`。

---

### 4.3 LLM 服务 (llm)

#### 4.3.1 ChatClient 配置

**文件**: `ChatClientConfig.java`

由于 `spring.ai.chat.client.enabled=false` 禁用了自动配置，手动定义两个 `ChatClient` Bean：

**deepseekClient** (文本对话):
- 包装 `DeepSeekChatModel` → 调用 `deepseek-chat` 模型
- System prompt 包含工具使用说明

**agnesClient** (多模态对话):
- 包装 `OpenAiChatModel` → 调用 `agnes-2.0-flash` 模型
- System prompt 额外包含参考图片传参说明

#### 4.3.2 LlmService 接口

```java
public interface LlmService {
    String chat(String text, String imageUrl, ChatClient client);
}
```

三个参数：
- `text` — 用户文本输入
- `imageUrl` — 可选图片 data URI（纯文本时为 null）
- `client` — 使用的 ChatClient（deepseekClient 或 agnesClient）

#### 4.3.3 LlmServiceImpl 实现

```java
public String chat(String text, String imageUrl, ChatClient client) {
    // 纯图片无文字时，使用默认提示词
    if (text.isBlank() && imageUrl != null) text = "请描述这张图片";

    return client.prompt()
        .user(userSpec -> {
            userSpec.text(finalText);
            if (imageUrl != null) {
                userSpec.media(new Media(MimeTypeUtils.IMAGE_JPEG, URI.create(imageUrl)));
            }
        })
        .tools(weatherTools, imageTools)    // 注册 @Tool 工具
        .call()
        .content();
}
```

核心功能：
1. 组装 user prompt（文本 + 可选的图片 media）
2. 注册 `WeatherTools` 和 `ImageTools` 两个工具
3. 等待 LLM 回复（LLM 自主决定是否调用工具）

#### 4.3.4 工具 (Tools)

##### ImageTools — 图片生成

```java
@Component
public class ImageTools {
    private final OpenAiImageModel agnesImageModel;  // 自动配置

    @Tool(description = "生成图片并返回图片URL...")
    public String generateImage(
            @ToolParam(description = "图片描述") String prompt,
            @ToolParam(description = "参考图片URL", required = false) String imageUrl) {

        ImagePrompt imagePrompt = new ImagePrompt(prompt,
            OpenAiImageOptions.builder()
                .model("agnes-image-2.1-flash")
                .height(1024).width(1024)
                .build());

        var response = agnesImageModel.call(imagePrompt);
        return "图片已生成，请在回复中原样输出以下URL：\n"
            + response.getResult().getOutput().getUrl();
    }
}
```

- 使用 `OpenAiImageModel`（Spring AI 自动配置，复用 `spring.ai.openai.*` 配置）
- 实际调用：`POST https://apihub.agnes-ai.com/v1/images/generations`，模型 `agnes-image-2.1-flash`
- `imageUrl` 参数已声明但尚未在方法体中实现（图生图功能待完成）

##### WeatherTools — 天气查询

```java
@Component
public class WeatherTools {
    private final WeatherService weatherService;

    @Tool(description = "查询指定城市的实时天气")
    public String getWeather(@ToolParam(description = "中文城市名") String city) {
        return weatherService.getWeatherText(city);
    }
}
```

- 委托 `WeatherService` 获取数据
- 返回格式化文本（如 "晴 26°C 南风 湿度60% 风力3级"）

#### 4.3.5 关于 OpenAiImageModel

`OpenAiImageModel` 是 Spring AI 框架提供的图片生成客户端，封装了 OpenAI 兼容的 `/v1/images/generations` API。它由 `spring-ai-starter-model-openai` 依赖自动创建 Bean，配置复用 `spring.ai.openai.*` 属性。

与 `ChatClient` 的区别：

| | `OpenAiImageModel` | `ChatClient` |
|---|---|---|
| **职责** | 图片生成（画图） | 文本对话（聊天） |
| **API** | POST `/v1/images/generations` | POST `/v1/chat/completions` |
| **角色** | "Model 客户端" | "Builder 包装器" |
| **创建方式** | 自动配置 | 手动 @Bean |
| **支持 Tool** | ❌ | ✅ `.tools()` |

---

### 4.4 天气服务 (weather)

#### 4.4.1 架构

```
WeatherController (REST)
    │ GET /weather/search?city=北京&type=base
    ▼
WeatherServiceImpl
    ├── resolveCityCode() → 高德行政区划 API → adcode
    ├── 高德天气 API (实时/预报)
    └── parseLive() / parseForecast() → WeatherResponse
```

#### 4.4.2 高德 API 调用

**实时天气**:
```
GET https://restapi.amap.com/v3/weather/weatherInfo
    ?key={key}&city={adcode}&extensions=base
```

**预报天气**:
```
GET https://restapi.amap.com/v3/weather/weatherInfo
    ?key={key}&city={adcode}&extensions=all
```

**城市编码查询**:
```
GET https://restapi.amap.com/v3/config/district
    ?key={key}&keywords={city_name}&subdistrict=0
```

#### 4.4.3 DTO 定义

所有 DTO 使用 Java `record` + Lombok `@Builder`：

- **`WeatherRequest`**: `{ city: String }`
- **`WeatherResponse`**: `{ type, province, city, weather, temperature, humidity, windDirection, windPower, reportTime, forecasts[] }`
- **`ForecastDay`**: `{ date, week, dayWeather, nightWeather, dayTemp, nightTemp, dayWind, nightWind, dayPower, nightPower }`

#### 4.4.4 WeatherController

```java
@RestController
public class WeatherController {
    @GetMapping("/weather/search")
    public WeatherResponse searchWeather(
            @RequestParam String city,
            @RequestParam(defaultValue = "base") String type) {
        return weatherService.getWeatherByCity(city, type);
    }
}
```

提供 REST API 接口，type=`base` 返回实时天气，type=`all` 返回预报。

---

### 4.5 异常处理 (exception)

#### ErrorCode 枚举

统一错误码定义：

| 错误码 | 描述 |
|--------|------|
| `API_ERROR` | 高德 API 调用失败 |
| `CITY_NOT_FOUND` | 未找到对应城市 |
| `NETWORK_ERROR` | 网络请求失败 |
| `ANALYSIS_ERROR` | 解析天气数据失败 |
| `AI_CALL_FAILED` | AI 服务不可用 |
| `AI_WEATHER_FAILED` | 天气查询失败 |

#### BusinessException

携带 `ErrorCode` 的业务异常，可在构造时指定自定义消息。

#### GlobalExceptionHandler

通过 `@RestControllerAdvice` 统一拦截 `BusinessException`，返回 HTTP 400 + JSON 错误体：

```json
{
    "code": "CITY_NOT_FOUND",
    "message": "未找到对应城市: xyz"
}
```

---

## 5. 核心调用链路

### 5.1 文本聊天流程

```
用户发送微信文本消息 "你好"
    │
    ▼
WeixinBotService.handleMessage()
    ├── extractText → "你好"
    ├── extractImageDataUri → null
    └── 路由 → deepseekClient (DeepSeek 文本模型)
            │
            ▼
    LlmServiceImpl.chat("你好", null, deepseekClient)
        └── client.prompt()
                .user("你好")
                .tools(weatherTools, imageTools)
                .call()
                .content()
            │
            ▼
    DeepSeek 返回文本回复
    └── extractUrl → null
        └── safeSendText("你好！有什么可以帮助你的吗？")
```

### 5.2 图片生成流程

```
用户发送微信消息 "帮我画一只猫"
    │
    ▼
WeixinBotService.handleMessage()
    ├── extractText → "帮我画一只猫"
    └── 路由 → deepseekClient
            │
            ▼
    LlmServiceImpl.chat("帮我画一只猫", null, deepseekClient)
        └── client.prompt()
                .user("帮我画一只猫")
                .tools(weatherTools, imageTools)
                .call()
            │
            ▼
    Spring AI 工具回调
    LLM 识别到画图意图 → 调用 generateImage("一只猫")
            │
            ▼
    ImageTools.generateImage("一只猫", null)
        ├── ImagePrompt → model: "agnes-image-2.1-flash", 1024x1024
        ├── OpenAiImageModel.call() → POST Agnes AI 图片 API
        └── 返回图片 URL
            │
            ▼
    LLM 将 URL 嵌入回复文本
            │
            ▼
    WeixinBotService.handleMessage() 处理回复
        ├── extractUrl → 匹配到 https://...
        ├── downloadImage → HTTP GET → byte[]
        └── safeSendImage → 图片发送到微信
```

### 5.3 天气查询流程

```
用户发送微信消息 "北京天气"
    │
    ▼
WeixinBotService.handleMessage()
    │
    ▼
LlmServiceImpl.chat("北京天气", null, deepseekClient)
    └── client.prompt()
            .user("北京天气")
            .tools(weatherTools, imageTools)
            .call()
        │
        ▼
Spring AI 工具回调
LLM 识别到天气查询 → 调用 getWeather("北京")
        │
        ▼
WeatherTools.getWeather("北京")
    └── WeatherServiceImpl.getWeatherText("北京")
        ├── resolveCityCode("北京") → adcode
        │   └── GET 高德行政区划 API → "110000"
        ├── GET 高德天气 API → JSON
        └── 格式化: "晴 26°C 南风 湿度60% 风力3级"
            │
            ▼
LLM 将天气数据组织成自然语言回复
    └── safeSendText("北京当前天气：晴 26°C...")
```

---

## 6. 配置说明

### application.properties

```properties
# 应用
spring.application.name=ykd

# 高德天气
gaode.key=xxxxxxxxxxxxxxxxxxxxxxxxx

# Spring AI ChatClient 禁用自动配置（手动定义 Bean）
spring.ai.chat.client.enabled=false

# Agnes AI (OpenAI 兼容接口) — 用于多模态聊天 + 图片生成
spring.ai.openai.api-key=sk-xxxx...
spring.ai.openai.base-url=https://apihub.agnes-ai.com/v1
spring.ai.openai.chat.options.model=agnes-2.0-flash

# DeepSeek — 用于纯文本聊天
spring.ai.deepseek.api-key=sk-xxxx...
spring.ai.deepseek.base-url=https://api.deepseek.com
spring.ai.deepseek.chat.options.model=deepseek-chat
```

### 关键配置说明

| 配置项 | 说明 |
|--------|------|
| `spring.ai.chat.client.enabled=false` | 禁用 Spring AI 的 ChatClient 自动配置，由 `ChatClientConfig` 手动创建两个 ChatClient Bean |
| `spring.ai.openai.*` | 虽然前缀是 `openai`，但 base-url 指向 Agnes AI，使用的是 OpenAI 兼容接口 |
| `gaode.key` | 高德开放平台 API Key，用于天气查询 |

---

## 7. API 接口

### 7.1 天气查询 REST API

**请求**:
```
GET /weather/search?city=北京&type=base
```

**参数**:

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `city` | String | 是 | — | 城市名或 adcode |
| `type` | String | 否 | `base` | `base`=实时, `all`=预报 |

**响应** (实时天气):
```json
{
    "type": "base",
    "province": "北京",
    "city": "北京市",
    "weather": "晴",
    "temperature": "26",
    "humidity": "60",
    "windDirection": "南风",
    "windPower": "3",
    "reportTime": "2024-01-15 10:00:00",
    "forecasts": null
}
```

**响应** (预报天气):
```json
{
    "type": "all",
    "province": "北京",
    "city": "北京市",
    "reportTime": "2024-01-15 10:00:00",
    "forecasts": [
        {
            "date": "2024-01-15",
            "week": "1",
            "dayWeather": "晴",
            "nightWeather": "多云",
            "dayTemp": "26",
            "nightTemp": "15",
            "dayWind": "南风",
            "nightWind": "南风",
            "dayPower": "3",
            "nightPower": "2"
        }
    ]
}
```

### 7.2 微信机器人接口

无 HTTP 接口。通过 `ILinkClient` 轮询微信消息，自动响应。

---

> **文档版本**: v1.0
> **最后更新**: 2026-07-18
