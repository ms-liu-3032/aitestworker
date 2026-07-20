# LLM 调用治理与异步生成说明

本文说明开源版中 LLM 调用、异步生成任务、错误分类和排障方式。

## 设计目标

- 长耗时生成不阻塞普通 HTTP 请求。
- 重复点击不会重复调用模型或重复生成草稿。
- 模型失败时返回可读、可分类的错误原因。
- 生成结果先进入草稿区，正式用例库仍需人工确认。
- 调用日志可追溯模型、耗时、重试次数和错误码，但不记录 API Key。

## 主链路

1. 用户在用例生成工作台输入需求。
2. 系统完成需求分析、风险扫描、澄清和测试点拆解。
3. 用户触发生成后，后端创建异步生成任务并返回 `taskId`。
4. 前端轮询任务状态。
5. 任务成功后，草稿箱刷新展示生成的用例草稿。
6. 用户确认后，草稿才进入正式用例库。

## 幂等保护

生成任务会按以下信息生成请求指纹：

- project id
- user id
- task type
- 输入内容
- prompt version / prompt fingerprint
- model config / model name

如果相同请求已有 `PENDING` 或 `RUNNING` 任务，后端直接返回已有 `taskId`，避免重复点击造成重复模型调用。

## 任务状态

`generation_task` 使用运行态状态：

- `PENDING`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `TIMEOUT`
- `CANCELED`

失败任务会记录 `error_code` 和 `error_message`，前端据此展示具体失败原因。

## 错误码

当前支持的主要错误码：

- `TIMEOUT`：模型调用超时
- `RATE_LIMITED`：模型服务限流
- `PROVIDER_ERROR`：模型服务端异常
- `AUTH_ERROR`：模型配置或密钥无效
- `INSUFFICIENT_BALANCE`：模型账户余额或额度不足
- `CONTEXT_TOO_LONG`：输入上下文超长
- `OUTPUT_PARSE_ERROR`：模型输出格式解析失败
- `INVALID_REQUEST`：请求参数不合法
- `UNKNOWN_ERROR`：未分类异常

可重试错误会进行有限重试；密钥错误、余额不足、上下文超长、非法请求和解析失败不会盲目重试。

## JSON 输出处理

后端统一清洗模型输出：

- 移除 markdown code fence。
- 从说明文本中提取 JSON 对象或数组。
- 校验任务所需字段。
- 解析失败时标记为 `FAILED + OUTPUT_PARSE_ERROR`。

解析失败不会写入正式资产。

## 排障建议

Docker 部署时优先查看后端容器日志：

```bash
docker logs aitest-server-backend --tail 200
```

如果 `.env` 修改了 `APP_NAME`，容器名会变为 `${APP_NAME}-backend`。

排查模型失败时重点看：

- 模型配置是否启用且 API Key 有效。
- 模型账户是否有余额或额度。
- 后端日志中的 `taskId`、`error_code`、模型名和耗时。
- 输入是否过长导致 `CONTEXT_TOO_LONG`。
