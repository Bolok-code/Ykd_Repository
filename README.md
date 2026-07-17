# ykd_repository

ykd 夏令营项目仓库

## 项目说明

- 技术栈：Spring Boot 4.1.0 + JDK 21 + Maven
- 构建工具：Maven Wrapper (3.9.11)
- 端口：默认 8080
- 启动类：ykd.ykd.YkdApplication

## 快速启动

```bash
.\mvnw spring-boot:run
```

## 目录结构

```
src/main/java/ykd/ykd/
├── YkdApplication.java                          # 启动类
├── exception/                                   # 异常体系
│   ├── ErrorCode.java                           #   错误码枚举
│   ├── BusinessException.java                   #   业务异常
│   └── GlobalExceptionHandler.java              #   @RestControllerAdvice 统一异常处理
├── weather/
│   ├── config/
│   │   └── RestTemplateConfig.java              # RestTemplate Bean 配置
│   ├── service/
│   │   ├── WeatherService.java                  # 天气服务接口
│   │   └── impl/
│   │       └── WeatherServiceImpl.java          # 天气服务实现（心知天气 HMAC-SHA1 签名）
│   └── api/
│       ├── WeatherController.java               # REST 接口
│       └── dto/
│           └── WeatherResponse.java             # 天气响应 DTO (record + @Builder)
└── wxbot/
    └── WeixinBotService.java                    # 微信机器人（@PostConstruct/@PreDestroy）
```

## API 接口

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | `/weather/search` | `city` (必填) | 查询城市实时天气 |

## 微信机器人

启动后自动进入微信扫码登录流程，登录成功后监听好友消息，收到城市名自动回复天气。