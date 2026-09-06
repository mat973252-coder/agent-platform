# Agent Platform

[![verify](https://github.com/mat973252-coder/agent-platform/actions/workflows/verify.yml/badge.svg)](https://github.com/mat973252-coder/agent-platform/actions/workflows/verify.yml)

Java + Temporal 的持久化 Agent 执行基础项目。

**当前阶段：P1.1 治理适配已实现。** 固定诊断工作流的模拟重启已通过真实 AgentPermit4j 管线，支持允许、策略拒绝、待审批和执行失败分支。审批及结果幂等仍使用本机演示组件；尚未接入真实模型、真实写工具、生产身份或跨 Worker 的副作用幂等。

## 技术与结构

- Java 21、Spring Boot 4.0.8、Temporal Java SDK 1.38.0。
- 本地 Temporal Server 1.31.0 使用 PostgreSQL 16 持久存储，Temporal UI 2.49.1。
- `agent-runtime-core`：纯 Java 领域数据。
- `durable-execution`：Workflow 与 Activity 契约。
- `agentpermit-adapter`：可信调用构造、AgentPermit 决策映射及本机治理 fixture。
- `platform-api`：Spring Boot API、Worker 与模拟工具。
- [详细 TODO](TODO.md)、[架构边界](docs/architecture.md)、[完整平台规划](production-agent-platform-tech-stack-and-core-features.md)。

## 构建与测试

需要 JDK 21、Python 3.9+ 和网络。AgentPermit 尚未发布到 Maven Central，先从固定公开提交构建依赖，再验证主项目：

```powershell
python scripts/bootstrap-agentpermit.py
.\mvnw.cmd -B -ntp verify
```

Linux/macOS：先运行 `python3 scripts/bootstrap-agentpermit.py`，再运行 `./mvnw -B -ntp verify`。

依赖锁定在 [`.mvn/agentpermit.lock.json`](.mvn/agentpermit.lock.json)：公开提交 `c33911c595e5718b144d2bdb939bb3e23e17c909`、版本 `0.2.0`，下载后校验 SHA-256。脚本构建必要模块并运行其测试；主项目使用 `var/maven-repository` 隔离仓库，不依赖其他 checkout 或用户全局 Maven 缓存。锁定更新后重新运行脚本；不自动跟随远端 main 或本机未发布分支。

37 项项目测试包括领域校验、真实 AgentPermit 决策、工作流分支、Activity 重试、稳定操作 ID、旧历史回放和真实 HTTP 请求；测试中的 Temporal 使用内存测试服务。依赖自身的 113 项测试单独记录。

## 本地启动

先启动 Docker，再运行：

```powershell
docker compose up -d --wait --wait-timeout 180
java -jar platform-api/target/platform-api-0.1.0-SNAPSHOT.jar
```

- API：`http://127.0.0.1:9090`；健康检查：`/actuator/health`。
- Temporal UI：`http://127.0.0.1:8233`；gRPC：`127.0.0.1:7233`。
- PostgreSQL 无宿主机公开端口。Compose 内的固定数据库口令仅供本机合成演示使用，不是生产配置。
- `docker compose down` 停止服务并保留命名卷；本项目启动脚本不自动删除数据库数据。
- 覆盖端口示例：`java -jar platform-api/target/platform-api-0.1.0-SNAPSHOT.jar --server.port=9092`。

## 提交并审批一个任务

```powershell
$body = @{
  requestId = 'demo-001'
  service = 'orders'
  approvalTimeoutSeconds = 300
} | ConvertTo-Json
$run = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:9090/api/runs -ContentType application/json -Body $body
$state = Invoke-RestMethod -Uri "http://127.0.0.1:9090$($run.statusUrl)"
$state
# 等 state 为 WAITING_APPROVAL 后，提交该 Run 返回的 approvalId。
$decision = @{ approvalId = $state.approvalId; decision = 'APPROVE' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:9090$($run.statusUrl)/approval" -ContentType application/json -Body $decision
Invoke-RestMethod -Uri "http://127.0.0.1:9090$($run.statusUrl)"
```

改为 `REJECT` 可拒绝；`POST /api/runs/{runId}/cancel` 请求取消。

| API | 结果 |
|---|---|
| `POST /api/runs` | 202；返回稳定 runId 与状态地址 |
| `GET /api/runs/{runId}` | 当前状态、合成证据、审批 ID、操作 ID 与结果 |
| `POST /api/runs/{runId}/approval` | 202 表示消息已提交，最终状态通过 GET 确认 |
| `POST /api/runs/{runId}/cancel` | 202 表示取消已请求 |

非法输入为 400，未知 Run 为 404，重复 requestId 或不匹配的审批/终态操作为 409。`requestId` 最长 64 个字符，仅支持字母、数字、下划线和连字符；已完成任务的 ID 也不能复用。

查询中的 `reasonCode` 表示最近一次工具治理决定的原因。策略拒绝为 `DENIED`，用户拒绝为 `REJECTED`。允许/拒绝/失败分支使用离线 fixture 验证；当前 API 的固定重启策略始终要求审批，不提供调用方切换策略的参数。

## 跨进程恢复验收

需要运行中的 Compose 和 Python 3 标准库。脚本自行启动打包后的 API（默认 9091），等待审批时强制结束自己创建的 Worker 进程，重新启动后查询并继续同一个 Run；随后检查拒绝、取消、超时和重复请求。结束时只停止脚本自己启动的应用进程。

```powershell
python scripts/smoke.py
```

日志写入忽略的 `var/smoke.log`。此验收与内存工作流测试分开，GitHub Actions 也会运行它。实际执行记录见 TODO。

2026-09-06 已在 [GitHub CI](https://github.com/mat973252-coder/agent-platform/actions/runs/34025622000) 通过 PostgreSQL + Temporal 的 Worker 强制终止/恢复验收，并验证拒绝、取消、超时和重复请求保护。本机 Windows 的 Docker 引擎启动故障使本地容器联调尚未完成；Windows 本地 Maven 测试已通过。

## 当前保证与限制

- Temporal 恢复已记录的执行结果；Activity 在完成结果尚未上报时仍可能重试。
- `operationId` 作为 AgentPermit 的幂等键，贯穿同一逻辑操作的重试；进程内可复用结果，跨 Worker/进程的副作用幂等尚未实现。
- 首个有效审批决定生效；并发提交可能都得到 202，只有最终工作流状态表示采用的决定。
- Adapter 使用 AgentPermit 的调用指纹校验，但本机演示会根据 Workflow 已记录的决定重新生成短期内存审批。它不代表独立审批人的授权，也不保证原审批跨进程、跨版本仍有效；持久审批及可信身份在 P1.2。
- `Workflow.getVersion` 保留 P0 的 Activity 序列；固定的审批中/已完成旧历史进入回放测试。旧 `executeAction` Activity 仅用于历史兼容，其模拟执行也通过治理管线。
- API、Temporal gRPC 与 UI 仅绑定 loopback；当前 Compose 是开发演示配置。
- 取消不会回滚已经完成的外部操作。
- 当前没有业务查询库、LLM/RAG、管理前端、SSE、Sandbox 或多 Runtime；这些在 TODO 分阶段列明。
- 原始平台规划描述最终愿景，不是当前能力清单。

## License

Apache-2.0，见 [LICENSE](LICENSE)。
