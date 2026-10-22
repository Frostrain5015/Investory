# 邮件发送网关

实现日期：2026-10-21。当前为本地实现，默认关闭，未部署，也未使用真实邮箱发送验收邮件。

## 范围

这是 Investory 后端中的独立 SMTP 模块，不需要观澜或常驻 Agent。提供配置状态查询、手动测试邮件和通用邮件发送接口。支持单个收件人、UTF-8 纯文本，以及带纯文本备用内容的 HTML 邮件。

本次不包含短信、交易审批、邮件回复处理、定时任务、自动重试、持久化队列、附件或通知设置界面。以后业务服务可调用 `EmailGateway.send(EmailMessage)`；本次没有向聊天、MCP 工具或策略运行流程增加任何调用。

## 参考方案

已核对 `D:/Nodejs Projects/PP Typeset/src/lib/server/email.js`：当前实际实现为 Nodemailer SMTP，使用 `SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS / SMTP_FROM`，默认 465 + SSL。该项目 `.env.example` 中的 Resend 示例与当前实现不一致，因此本次以实际代码为准。

Investory 采用同一 SMTP 路线及环境变量命名，使用 Spring Boot 自带版本管理的 `spring-boot-starter-mail` 和 `JavaMailSender`，无需额外运行 Node 服务。没有读取、复制或共享 PP Typeset 的真实邮箱凭据，也没有照搬其验证码日志和失败时静默跳过通知的行为。

参考：[Spring Boot 邮件配置与超时说明](https://docs.spring.io/spring-boot/3.3/reference/io/email.html)。

## 配置

`backend/src/main/resources/mail-gateway.properties` 由邮件配置类独立加载，不依赖本机被 Git 忽略的 `application.properties`。支持下列环境变量，也可通过标准 `investory.mail.*` Spring 配置覆盖。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `MAIL_GATEWAY_ENABLED` | `false` | 邮件总开关；仅显式打开后才允许发送 |
| `SMTP_HOST` | 空 | 邮箱服务商的 SMTP 服务器 |
| `SMTP_PORT` | `465` | SMTP 端口，范围 1–65535 |
| `SMTP_USER` | 空 | SMTP 登录账号 |
| `SMTP_PASS` | 空 | SMTP 授权码或密码，只配置在后端运行环境 |
| `SMTP_FROM` | 与 `SMTP_USER` 相同 | 发件人；可写 `Investory <实际发件邮箱>` |
| `SMTP_SECURITY` | `SSL` | `SSL` 通常配 465；`STARTTLS` 通常配 587，具体以服务商为准 |
| `SMTP_TIMEOUT_MS` | `5000` | 连接、读取、写入各自的超时，范围 1–60000 毫秒；不是整个请求的总时限 |

`PLAIN` 仅允许连接本机地址，供模拟 SMTP 测试使用。正式通道使用 SSL 或强制 STARTTLS，并校验证书主机名；不提供跳过证书检查选项。

配置检查不会连接 SMTP，也不会在应用启动时发信。缺失参数可通过状态接口查看；禁用或缺失配置时，发送请求明确报错。配置值类型错误（例如端口不是数字）会被 Spring 启动绑定检查拒绝。

## 接口与权限

路径相对于后端根地址；若运行环境设置 `/investory` 上下文路径，则在以下路径前加 `/investory`。所有接口要求现有管理员登录会话，普通用户和现有 MCP Bearer token 均不具备发送权限。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/admin/mail/status` | 查询开关、配置是否齐全及缺失/无效的变量名称，不返回密码等配置值 |
| `POST /api/admin/mail/test` | 向指定邮箱发送固定内容的通道测试邮件 |
| `POST /api/admin/mail/send` | 发送自定义主题及正文 |

POST 必须使用 `Content-Type: application/json`。本次没有新增免登录入口或独立网关密钥。

测试邮件请求：

```json
{"to":"收件人的实际邮箱"}
```

通用发送请求：

```json
{
  "to": "recipient@example.com",
  "subject": "Investory 测试通知",
  "text": "这是一条手动测试通知。",
  "html": "<p>这是一条手动测试通知。</p>"
}
```

`html` 可省略或为 null；`text` 始终必填。收件人仅支持一个不带显示名的邮箱，不支持抄送、密送、地址列表或邮件组。主题最多 200 字符，纯文本正文最多 50000 字符，HTML 最多 100000 字符。主题和地址拒绝换行与控制字符。发件人只能来自后端配置，调用方不能覆盖。

SMTP 确认接受后的返回示例：

```json
{
  "status": "accepted",
  "provider": "smtp",
  "requestId": "本次请求标识",
  "messageId": "SMTP 邮件的 Message-ID",
  "acceptedAt": "2026-10-21T00:00:00Z"
}
```

**accepted 只表示 SMTP 提交已获确认，不表示收件箱已送达。** 本次没有投递回执、退信接收或收件人已读追踪。

错误状态：

| HTTP | 含义 |
| --- | --- |
| 400 | 请求或邮件参数不正确 |
| 401 / 403 | 未登录 / 非管理员 |
| 503 + `MAIL_DISABLED` | 网关未启用 |
| 503 + `MAIL_NOT_CONFIGURED` | 必要配置缺失或无效 |
| 502 + `SMTP_SEND_UNCONFIRMED` | SMTP 异常，未能确认提交结果；不自动重试 |

日志仅记录请求标识、提交状态与异常类型，不记录正文、收件邮箱、SMTP 密码或服务商异常原文。日志不替代持久化发送历史。网络中断可能发生在服务商已接受邮件之后，因此不能看到 502 就立即自动重发；当前也不承诺跨请求去重。

## 验证与使用前准备

自动测试使用内存对象、MockMvc 和只监听 `127.0.0.1` 的临时 SMTP 服务，不连接真实邮箱、真实数据库或现有 Agent。覆盖权限、配置加载、禁用状态、邮箱/主题校验、中文正文与 HTML MIME、SMTP 协议提交、超时配置和异常脱敏。

本次验证结果：新增 23 项测试全部通过；全量后端测试 55 项，52 项通过、3 项原有 `CrawlerSchedulerTest` 跳过，无失败。SSL/STARTTLS 的配置已验证，但尚未与真实邮件服务商进行 TLS 握手、认证或收件验收。`git diff --check` 通过。

运行测试时禁用自动前端构建，避免覆盖之前的前端修改：

```powershell
$env:JAVA_HOME = 'E:\Java\jdk-17'
& 'E:\Maven\apache-maven-3.9.16\bin\mvn.cmd' -f backend/pom.xml '-P!build-windows,!build-unix' test
```

实际启用前需要配置发件邮箱的 SMTP 信息，再使用管理员登录会话向指定收件邮箱发送一封测试邮件，并人工确认收件。该步骤尚未执行。此次不启动或重启现有服务，不部署云端。
