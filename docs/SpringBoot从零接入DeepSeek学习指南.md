# Spring Boot 从零接入 DeepSeek：按问题一步步构建

## 1. 这份文档要解决什么

本指南不要求先背项目结构，而是按照下面的顺序学习：

1. 先明确一次聊天请求究竟经过哪些环节。
2. 先用最少代码验证 DeepSeek 能被调用。
3. 当代码出现职责混乱时，再拆出对应的类。
4. 理解每个类、注解、构造方法和核心调用为什么这样写。
5. 最后得到一套可以继续增加微信、天气、图片和语音功能的结构。

第一版只实现文字聊天，不急着加入数据库、天气、图片、语音和多轮记忆。

---

## 2. 先建立整体认识

假设手机向 Spring Boot 发送下面的请求：

```json
{
  "userId": "user-001",
  "message": "你好，请介绍一下杭州"
}
```

程序需要完成四件事：

```text
接收手机请求
→ 取出用户问题
→ 调用 DeepSeek
→ 把回答返回手机
```

最终我们会使用下面的分工：

```text
ChatController
    ↓ 接收请求
ChatService
    ↓ 定义聊天能力
ChatServiceImpl
    ↓ 实现聊天业务
ChatClient
    ↓ 由 Spring AI 提供
DeepSeek API
```

先不要急着创建所有类。下面按照“为什么需要它”的顺序逐个建立。

---

## 3. 第零步：创建项目和选择技术

在 Spring Initializr 中选择：

- Maven
- Java 21
- Spring Boot 4.1.x
- Spring Web MVC
- Validation

再通过 Maven 加入 Spring AI OpenAI Starter。

为什么连接 DeepSeek，却使用 OpenAI Starter？

因为 DeepSeek 提供了兼容 OpenAI Chat Completions 格式的接口。Spring AI 按照
OpenAI 格式组装请求，但把请求地址换成 DeepSeek，就可以调用 DeepSeek。

也就是说：

```text
Spring AI OpenAI Starter
并不等于
只能调用 OpenAI
```

只要另一个平台兼容相应的 API 格式，就可以通过修改基础地址接入。

### 3.1 Maven 中需要的核心依赖

```xml
<properties>
    <java.version>21</java.version>
    <spring-ai.version>2.0.0</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
</dependencies>
```

每个依赖分别解决什么问题？

| 依赖 | 解决的问题 |
|---|---|
| `spring-boot-starter-webmvc` | 启动 HTTP 服务，让手机、网页或 Postman 能访问项目 |
| `spring-boot-starter-validation` | 校验用户提交的数据，例如消息不能为空 |
| `spring-ai-starter-model-openai` | 创建模型连接对象、组装请求、鉴权、解析模型回答 |
| `spring-ai-bom` | 统一管理 Spring AI 各模块版本，避免依赖版本互相冲突 |

---

## 4. 第一步：为什么需要启动类

创建：

```text
DeepSeekApplication.java
```

代码：

```java
package com.example.deepseek;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DeepSeekApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepSeekApplication.class, args);
    }
}
```

### 4.1 为什么 Java 项目必须有这个类

Java 程序需要从 `main()` 方法开始运行。没有 `main()`，JVM 不知道应该从哪里启动。

### 4.2 `@SpringBootApplication` 为什么要写

它主要告诉 Spring Boot：

```text
从当前包开始扫描项目
→ 找到 Controller、Service、Configuration 等组件
→ 根据依赖和配置执行自动配置
→ 创建并管理这些对象
```

因此启动类应该放在项目的最上层包：

```text
com.example.deepseek
```

其他类放在它的子包中：

```text
com.example.deepseek.controller
com.example.deepseek.service
com.example.deepseek.config
```

如果启动类放得太深，Spring 可能扫描不到外面的类。

### 4.3 `SpringApplication.run()` 做了什么

它不是在调用 DeepSeek，而是在启动整个 Spring 应用：

```text
读取 application.yml
→ 创建 Spring 容器
→ 扫描组件
→ 创建 Bean
→ 执行自动配置
→ 启动 Tomcat
→ 等待 HTTP 请求
```

启动类只是“总开关”，不要把聊天业务写进这里。

---

## 5. 第二步：为什么必须配置地址、密钥和模型

创建：

```text
src/main/resources/application.yml
```

```yaml
server:
  port: 8080

spring:
  application:
    name: deepseek-chat

  ai:
    model:
      chat: openai

    openai:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}

      chat:
        model: deepseek-v4-flash
```

Spring AI 已经知道怎样按照 OpenAI 格式调用模型，但它不知道：

- 应该访问哪个模型平台；
- 使用哪个 API Key；
- 使用哪个模型。

所以配置分别承担下面的作用：

```yaml
base-url: https://api.deepseek.com
```

表示把请求发送给 DeepSeek，而不是 OpenAI。

```yaml
api-key: ${DEEPSEEK_API_KEY}
```

表示从操作系统环境变量读取密钥。

```yaml
model: deepseek-v4-flash
```

表示本项目默认调用的模型名称。

### 5.1 为什么不直接把密钥写进 YAML

下面这种写法虽然能运行，但不安全：

```yaml
api-key: sk-真实密钥
```

原因是：

- YAML 通常会被提交到 Git；
- 仓库成员都能看到密钥；
- Git 历史中的密钥即使后来删除，也可能继续存在；
- 手机客户端更不能保存服务端 API Key。

正确方式是在 IDEA 的运行配置中添加：

```text
DEEPSEEK_API_KEY=你的新密钥
```

`${DEEPSEEK_API_KEY}` 的含义是：

```text
启动时查找名为 DEEPSEEK_API_KEY 的环境变量
→ 找到后把值交给 Spring AI
→ Spring AI 用它生成 Authorization 请求头
```

---

## 6. 第三步：先只建 Controller，验证链路

目前项目能够启动，也有了模型配置，但是还没有入口接收用户问题。

因此第一个业务类应该是：

```text
controller/ChatController.java
```

第一版先写成：

```java
package com.example.deepseek.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping
    public String chat(@RequestParam String message) {
        return chatClient
                .prompt()
                .user(message)
                .call()
                .content();
    }
}
```

这一版不是最终结构，只用来验证 DeepSeek 是否接通。

### 6.1 为什么需要 Controller

手机和网页不能直接调用 Java 对象，它们通过 HTTP 与服务器通信。

Controller 的职责是把：

```text
HTTP 请求
```

转换成：

```text
Java 方法调用
```

访问：

```text
GET http://localhost:8080/api/chat?message=你好
```

Spring 会执行：

```java
chat("你好")
```

### 6.2 `@RestController` 为什么要写

它告诉 Spring：

1. 这是一个接收 HTTP 请求的类；
2. 方法返回值直接写入 HTTP 响应体；
3. 如果返回对象，Spring 会自动转换成 JSON。

如果不写这个注解，Spring 不会把它当作接口控制器。

### 6.3 `@RequestMapping("/api/chat")` 为什么写在类上

它给这个控制器中的所有接口统一添加路径前缀。

例如：

```java
@RequestMapping("/api/chat")
```

配合：

```java
@GetMapping
```

最终路径就是：

```text
/api/chat
```

这样以后还可以继续增加：

```text
POST   /api/chat
DELETE /api/chat/history
GET    /api/chat/status
```

### 6.4 为什么构造方法需要 `ChatClient.Builder`

```java
public ChatController(ChatClient.Builder builder)
```

项目中没有手动执行：

```java
new ChatClient.Builder(...)
```

因为 Spring AI Starter 读取配置后，会自动创建：

```text
OpenAiChatModel
ChatClient.Builder
```

Spring 创建 `ChatController` 时发现它需要 `ChatClient.Builder`，就会从容器中找出
这个对象并传入构造方法。这就是构造器依赖注入。

### 6.5 为什么字段使用 `private final`

```java
private final ChatClient chatClient;
```

- `private`：只有当前类能直接访问，避免其他类随意修改；
- `final`：构造完成后不能重新指向另一个对象；
- 构造器注入：明确表示“没有 ChatClient，这个 Controller 就不能工作”。

相比在字段上直接写 `@Autowired`，构造器注入的依赖更清晰，也更容易测试。

### 6.6 核心调用为什么这样写

```java
chatClient
        .prompt()
        .user(message)
        .call()
        .content();
```

逐步解释：

#### `prompt()`

创建一次新的模型请求。每次用户提问都应该从一个新的请求构建过程开始。

#### `user(message)`

把 `message` 放入角色为 `user` 的消息中。发送给模型的数据大致包含：

```json
{
  "role": "user",
  "content": "你好"
}
```

#### `call()`

真正执行网络请求并等待 DeepSeek 返回结果。它是同步调用，因此当前线程会等待模型回答。

#### `content()`

DeepSeek 返回的并不只是文字，还可能包含模型信息、Token 使用量、结束原因等数据。
`content()` 表示只取最终回答文字。

Spring AI 在底层大致完成：

```text
生成请求 JSON
→ 添加 Authorization: Bearer API_KEY
→ POST 到 DeepSeek
→ 接收 JSON 响应
→ 转换成 ChatResponse
→ 取出回答文字
```

### 6.7 为什么此时先不创建其他类

这一阶段只验证三件事：

1. Spring Boot 能不能正常启动；
2. API Key 和模型配置是否正确；
3. Spring AI 能不能成功请求 DeepSeek。

如果这里没有跑通，就不应该继续增加 Service、数据库和聊天记忆，否则排查范围会越来越大。

---

## 7. 第四步：发现 Controller 职责太多，拆出 Service

当调用验证成功以后，会发现 Controller 同时承担了两种职责：

```text
接收 HTTP 请求
调用 DeepSeek
```

以后再加入天气、历史消息、敏感词处理和模型切换，Controller 会不断变长：

```java
public String chat(String message) {
    // 判断参数
    // 判断天气
    // 加载聊天历史
    // 调用 DeepSeek
    // 保存回答
    // 转换异常
}
```

Controller 应该只关心“请求怎么进来、响应怎么出去”，不应该关心模型调用细节。

因此这时才拆出 Service。

---

## 8. 第五步：为什么先建 ChatService 接口

创建：

```text
service/ChatService.java
```

```java
package com.example.deepseek.service;

public interface ChatService {

    String chat(String userId, String message);
}
```

### 8.1 接口解决了什么问题

接口只规定能力：

```text
输入 userId 和 message
必须返回一段回答
```

它不规定底层必须使用 DeepSeek。

以后可能存在：

```text
ChatService
├─ DeepSeekChatServiceImpl
├─ BailianChatServiceImpl
└─ TestChatServiceImpl
```

Controller 只依赖 `ChatService`，不依赖某一家模型厂商。

### 8.2 为什么参数中先保留 `userId`

第一版单轮聊天暂时用不到 `userId`，但它以后可以用于：

- 隔离不同用户的聊天记录；
- 做访问频率限制；
- 记录用户会话；
- 清除指定用户的历史；
- 统计调用情况。

如果项目确定永远只有单用户，也可以先只保留 `message`。但手机聊天和微信机器人一般都需要区分用户。

### 8.3 接口为什么没有 `@Service`

接口不能提供完整的业务实现，Spring 无法直接创建它的可用对象。

`@Service` 应该放在实现类上，而不是只放在接口上。

---

## 9. 第六步：为什么需要 ChatServiceImpl

创建：

```text
service/impl/ChatServiceImpl.java
```

```java
package com.example.deepseek.service.impl;

import com.example.deepseek.service.ChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    public ChatServiceImpl(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String chat(String userId, String message) {
        String answer = chatClient
                .prompt()
                .user(message)
                .call()
                .content();

        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("DeepSeek 没有返回有效文字");
        }

        return answer;
    }
}
```

### 9.1 为什么它要 `implements ChatService`

```java
implements ChatService
```

表示这个类接受 `ChatService` 规定的契约，必须实现：

```java
String chat(String userId, String message);
```

如果方法名、参数或返回值不符合接口要求，编译器会直接报错。

### 9.2 `@Service` 为什么要写

它告诉 Spring：

```text
这是业务层组件
→ 启动时创建这个对象
→ 保存到 Spring 容器
→ 其他类需要 ChatService 时可以注入它
```

`@Service` 在技术上也是一种组件注解，但它能更明确地表达“这是业务逻辑类”。

### 9.3 `@Override` 为什么要写

它告诉编译器：

```text
这个方法是在实现父接口的方法
```

如果不小心把方法写成：

```java
public String chats(...)
```

编译器会立即指出它没有正确实现接口。

### 9.4 为什么要校验空回答

网络请求成功不代表一定有有效文字。模型服务可能返回空内容或异常结构。

如果不检查：

```java
return answer;
```

Controller 可能向手机返回空响应，用户只会觉得机器人没有回复。

显式抛出异常后，可以由统一异常处理器记录并转换成友好提示。

---

## 10. 第七步：为什么需要 DeepSeekChatConfig

前面的第一版 Controller 直接使用 Builder 创建 ChatClient。拆出 Service 后，我们希望
整个项目只创建和配置一次 ChatClient。

创建：

```text
config/DeepSeekChatConfig.java
```

```java
package com.example.deepseek.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DeepSeekChatConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        你是一个中文智能助手。
                        回答应该准确、简洁、自然。
                        不确定的内容要明确说明。
                        """)
                .build();
    }
}
```

### 10.1 为什么不让每个 Service 都执行 `builder.build()`

如果多个类分别创建 ChatClient：

- 系统提示词可能不一致；
- 日志和记忆配置可能不一致；
- 修改公共配置时需要改很多地方；
- 很难确认项目究竟使用了哪个 ChatClient。

配置类把对象创建规则集中到一个地方。

### 10.2 `@Configuration` 为什么要写

它告诉 Spring：

```text
这个类不是普通业务类
→ 它专门负责声明和组装 Bean
```

`proxyBeanMethods = false` 表示这个配置类中的 Bean 方法不需要通过代理互相调用。
当前配置比较简单，关闭代理可以减少不必要的处理。

### 10.3 `@Bean` 为什么写在方法上

```java
@Bean
public ChatClient chatClient(...)
```

表示：

```text
Spring 启动时执行这个方法
→ 得到一个 ChatClient
→ 把它保存进 Spring 容器
```

之后 `ChatServiceImpl` 构造方法需要 `ChatClient` 时，Spring 会把这个 Bean 传进去。

### 10.4 `defaultSystem()` 为什么放在配置类

系统提示词用于规定机器人长期身份：

```text
你是谁
使用什么语言
回答风格是什么
不知道时应该怎么办
```

这是全局模型配置，不属于某一次用户请求，所以放在 ChatClient 的统一配置中比较合适。

### 10.5 Builder 和 ChatClient 是什么关系

```text
ChatClient.Builder：建造 ChatClient 的工具
ChatClient：真正用于发起聊天请求的对象
```

Spring AI 自动提供 Builder；项目配置类利用 Builder 生成符合本项目要求的 ChatClient。

---

## 11. 第八步：让 Controller 只负责 HTTP

现在修改 Controller，让它不再直接接触 ChatClient：

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public String chat(
            @RequestParam String userId,
            @RequestParam String message) {

        return chatService.chat(userId, message);
    }
}
```

Spring 注入时发生的是：

```text
ChatController 需要 ChatService
→ Spring 查找实现 ChatService 的 Bean
→ 找到带 @Service 的 ChatServiceImpl
→ 把 ChatServiceImpl 传入 Controller
```

此时每层职责变得明确：

```text
Controller：HTTP 协议
Service：聊天业务
ChatClient：模型协议
```

微信机器人以后不需要调用 Controller，可以直接复用：

```java
String answer = chatService.chat(userId, text);
```

因此同一套聊天业务可以同时服务：

- 手机网页；
- App；
- 微信机器人；
- 命令行工具；
- 自动化任务。

---

## 12. 第九步：为什么要用 DTO，而不是一直堆请求参数

当参数只有一个时，下面的写法还能接受：

```text
/api/chat?message=你好
```

但以后参数可能增加为：

```text
userId
conversationId
message
model
voiceReply
```

继续使用很多 `@RequestParam` 会让接口难以维护，所以创建 DTO。

### 12.1 ChatRequest

创建：

```text
dto/ChatRequest.java
```

```java
package com.example.deepseek.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "用户ID不能为空")
        String userId,

        @NotBlank(message = "消息不能为空")
        String message
) {
}
```

为什么用 `record`？

DTO 的主要任务是携带数据，不需要复杂业务方法。`record` 会自动提供：

- 构造方法；
- 字段访问方法；
- `equals()`；
- `hashCode()`；
- `toString()`。

为什么使用 `@NotBlank`？

它不仅拒绝 `null`，也拒绝空字符串和只有空格的字符串。

### 12.2 ChatResponse

```java
package com.example.deepseek.dto;

public record ChatResponse(
        String userId,
        String answer
) {
}
```

响应也使用 DTO，是为了让返回格式保持稳定：

```json
{
  "userId": "user-001",
  "answer": "你好，我是你的智能助手。"
}
```

以后增加 `model`、`requestId` 或时间戳时，不需要把业务层返回值改成一段手工拼接的 JSON。

### 12.3 Controller 改成 POST

```java
@PostMapping
public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
    String answer = chatService.chat(
            request.userId(),
            request.message()
    );

    return new ChatResponse(request.userId(), answer);
}
```

各注解含义：

- `@PostMapping`：该方法接收 POST 请求；
- `@RequestBody`：把请求 JSON 转换成 `ChatRequest`；
- `@Valid`：执行 `@NotBlank` 等参数校验；
- 返回 `ChatResponse`：Spring 自动转换成 JSON。

---

## 13. 第十步：为什么要统一处理异常

DeepSeek 调用可能因为以下原因失败：

- API Key 错误；
- 余额不足；
- 模型名称错误；
- 请求超时；
- 网络错误；
- HTTP 429 限流；
- DeepSeek 服务异常；
- 模型返回空结果。

如果每个 Controller 都写一遍 `try-catch`，错误处理会散落在各处。

因此创建：

```text
exception/GlobalExceptionHandler.java
```

```java
package com.example.deepseek.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handleException(Exception exception) {
        LOG.error("AI 服务调用失败", exception);

        return Map.of(
                "code", "AI_REQUEST_FAILED",
                "message", "AI 服务暂时不可用，请稍后重试"
        );
    }
}
```

### 13.1 为什么日志和用户提示要分开

日志需要保存详细原因，便于开发者排查：

```text
HTTP 状态码
异常类型
请求失败位置
服务端错误信息
```

用户只需要看到安全、清楚的提示：

```text
AI 服务暂时不可用，请稍后重试
```

不能把完整堆栈、服务器路径或 API Key 返回给手机。

### 13.2 为什么它叫全局异常处理器

`@RestControllerAdvice` 会拦截所有 Controller 抛出的匹配异常。

这样 Service 只负责抛出异常，Controller 不需要重复处理。

实际项目中还应该继续细分：

```text
参数错误        → HTTP 400
认证或配置错误  → HTTP 500/503
限流            → HTTP 429/503
上游模型错误    → HTTP 502
请求超时        → HTTP 504
```

---

## 14. Spring 启动时到底发生了什么

按顺序理解：

```text
1. JVM 执行 DeepSeekApplication.main()
2. SpringApplication.run() 创建 Spring 容器
3. Spring 读取 application.yml
4. Spring AI Starter 发现 OpenAI 模型配置
5. Spring AI 创建 OpenAiChatModel
6. Spring AI 创建 ChatClient.Builder
7. Spring 执行 DeepSeekChatConfig.chatClient()
8. 配置类使用 Builder 创建 ChatClient Bean
9. Spring 创建 ChatServiceImpl，并注入 ChatClient
10. Spring 创建 ChatController，并注入 ChatServiceImpl
11. Tomcat 监听 8080 端口
12. 项目等待用户请求
```

这里最容易混淆的是：

```text
OpenAiChatModel
```

虽然名字中有 OpenAI，但它实际使用的是：

```yaml
base-url: https://api.deepseek.com
```

所以请求发送到 DeepSeek。

---

## 15. 用户发送一次消息时发生了什么

假设手机发送：

```http
POST /api/chat
Content-Type: application/json
```

```json
{
  "userId": "user-001",
  "message": "请介绍一下杭州"
}
```

运行过程：

```text
1. Tomcat 收到 HTTP 请求
2. Spring 根据路径找到 ChatController.chat()
3. @RequestBody 把 JSON 转换成 ChatRequest
4. @Valid 校验 userId 和 message
5. Controller 调用 ChatService.chat()
6. 实际执行对象是 ChatServiceImpl
7. ChatServiceImpl 使用 ChatClient 构建 Prompt
8. Spring AI 加入系统消息和用户消息
9. Spring AI 添加 Bearer API Key
10. Spring AI 向 DeepSeek 发起 HTTP 请求
11. DeepSeek 返回回答
12. content() 取出回答文字
13. Service 把文字交还 Controller
14. Controller 创建 ChatResponse
15. Spring 把 ChatResponse 转成 JSON
16. 手机收到回答
```

简化成核心流程：

```text
JSON
→ ChatController
→ ChatServiceImpl
→ ChatClient
→ DeepSeek
→ ChatResponse
→ JSON
```

---

## 16. 最终各类存在的理由

| 类或文件 | 为什么需要 | 不应该负责什么 |
|---|---|---|
| `DeepSeekApplication` | 启动 Spring Boot、组件扫描和自动配置 | 不写聊天业务 |
| `application.yml` | 保存地址、模型名和环境变量引用 | 不保存真实密钥 |
| `DeepSeekChatConfig` | 统一创建 ChatClient、设置系统提示词 | 不接收 HTTP 请求 |
| `ChatController` | 接收请求、校验 DTO、返回响应 | 不直接管理模型配置 |
| `ChatService` | 定义稳定的聊天能力契约 | 不提供具体实现 |
| `ChatServiceImpl` | 实现聊天业务、调用 ChatClient | 不处理 HTTP JSON |
| `ChatRequest` | 规定请求数据结构 | 不调用 Service |
| `ChatResponse` | 规定响应数据结构 | 不调用模型 |
| `GlobalExceptionHandler` | 统一转换和记录异常 | 不实现正常聊天流程 |
| `ChatClient` | 构建 Prompt 并调用模型 | 不处理手机或微信协议 |

---

## 17. 最终推荐目录

```text
src/main/java/com/example/deepseek/
├─ DeepSeekApplication.java
├─ config/
│  └─ DeepSeekChatConfig.java
├─ controller/
│  └─ ChatController.java
├─ dto/
│  ├─ ChatRequest.java
│  └─ ChatResponse.java
├─ service/
│  ├─ ChatService.java
│  └─ impl/
│     └─ ChatServiceImpl.java
└─ exception/
   └─ GlobalExceptionHandler.java

src/main/resources/
└─ application.yml
```

这不是为了让目录显得复杂，而是为了让每个类只有一个主要变化原因：

```text
HTTP 接口变化        → 修改 Controller/DTO
聊天业务变化         → 修改 Service
模型公共配置变化     → 修改 Config
错误返回规则变化     → 修改 ExceptionHandler
启动方式变化         → 修改 Application
```

---

## 18. 在 IDEA 中运行和验证

### 18.1 配置环境变量

打开：

```text
Run
→ Edit Configurations
→ Environment variables
```

添加：

```text
DEEPSEEK_API_KEY=你的新密钥
```

然后运行 `DeepSeekApplication.main()`。

### 18.2 使用 PowerShell 测试

```powershell
$body = @{
    userId = "user-001"
    message = "你好，请介绍一下杭州"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/chat" `
    -ContentType "application/json" `
    -Body $body
```

如果能够收到 `answer`，说明下面的链路已经全部成功：

```text
HTTP
→ Controller
→ Service
→ Spring AI
→ DeepSeek
→ HTTP 响应
```

---

## 19. 第一版完成后再增加什么

建议按照下面顺序继续扩展：

### 19.1 多轮会话

为每次请求增加 `conversationId`，通过 Spring AI ChatMemory 或自己维护的会话仓库保存历史。

需要注意：大模型本身是无状态的。所谓“记住上文”，本质上是程序在下一次请求中重新把历史消息发给模型。

### 19.2 微信机器人

ILink 收到微信消息后，不需要重新实现 DeepSeek 调用，只调用：

```java
String answer = chatService.chat(userId, text);
```

然后由 ILink SDK 把 `answer` 发回微信。

### 19.3 天气能力

在路由层判断是否为真正的天气查询：

```text
天气查询 → WeatherService
普通聊天 → ChatService
```

天气接口负责提供真实数据，DeepSeek 可以负责把结构化天气数据组织成自然语言，但不能让模型凭空猜天气。

### 19.4 图片和语音

继续保持职责分离：

```text
ImageUnderstandingClient：识图
ImageGenerationClient：生图
SpeechSynthesisClient：文字转语音
ILinkClient：微信媒体收发
```

不要把所有供应商 HTTP 请求全部写进 `ChatServiceImpl`。

---

## 20. 必须真正理解的核心思想

### 20.1 Spring AI 负责模型通信

```text
请求 JSON、鉴权、HTTP 调用、响应解析
```

### 20.2 Service 负责业务

```text
该问哪个模型、是否保存历史、失败如何处理
```

### 20.3 Controller 负责协议入口

```text
把 HTTP 请求转换为 Java 调用，再把 Java 对象转换为 HTTP 响应
```

### 20.4 Spring 容器负责组装对象

代码不在各处执行大量 `new`，而是通过构造器声明依赖：

```java
public ChatServiceImpl(ChatClient chatClient)
```

这句话表达的是：

```text
ChatServiceImpl 必须依赖 ChatClient 才能工作
```

Spring 根据配置找到对应对象并注入。

### 20.5 核心代码很短，架构是为了让它长期可维护

真正调用 DeepSeek 的代码只有：

```java
chatClient
        .prompt()
        .user(message)
        .call()
        .content();
```

其他类不是为了“凑结构”，而是分别解决：

```text
怎么启动
怎么配置
怎么接收请求
怎么组织业务
怎么描述数据
怎么处理错误
怎么方便以后替换模型
```

学习时应该先跑通最小版本，再根据实际问题逐层拆分。这样你记住的不是固定目录，而是每个类产生的原因。

