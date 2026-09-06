# Agent Platform

Java + Temporal 的持久化 Agent 执行基础项目。

**当前阶段：可运行骨架。** 提供固定诊断工作流、本机 REST API、审批等待、拒绝、超时、取消、有限重试和执行状态查询。工具使用合成数据与模拟操作，尚未接入真实模型、AgentPermit4j 或真实写工具。

## 技术与结构

- Java 21、Spring Boot 4.0.8、Temporal Java SDK 1.38.0。
- 本地 Temporal Server 1.31.0 使用 PostgreSQL 16 持久存储，Temporal UI 2.49.1。
- `agent-runtime-core`：纯 Java 领域数据。
- `durable-execution`：Workflow 与 Activity 契约。
- `platform-api`：Spring Boot API、Worker 与模拟工具。
- [详细 TODO](TODO.md)、[架构边界](docs/architecture.md)、[完整平台规划](production-agent-platform-tech-stack-and-core-features.md)。

## 构建与测试

需要 JDK 21。Maven Wrapper 自动下载 Maven 和公开依赖；无需全局安装 Maven、Docker、模型 key 或其他本地项目。

```powershell
.\mvnw.cmd -B -ntp verify
```

Linux/macOS：`./mvnw -B -ntp verify`。

测试包括领域校验、工作流分支、Activity 重试、稳定操作 ID、历史回放和真实 HTTP 请求；测试中的 Temporal 使用内存测试服务。

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

## 跨进程恢复验收

需要运行中的 Compose 和 Python 3 标准库。脚本自行启动打包后的 API（默认 9091），等待审批时强制结束自己创建的 Worker 进程，重新启动后查询并继续同一个 Run；随后检查拒绝、取消、超时和重复请求。结束时只停止脚本自己启动的应用进程。

```powershell
python scripts/smoke.py
```

日志写入忽略的 `var/smoke.log`。此验收与内存工作流测试分开，GitHub Actions 也会运行它。实际执行记录见 TODO。

## 当前保证与限制

- Temporal 恢复已记录的执行结果；Activity 在完成结果尚未上报时仍可能重试。
- `operationId` 在同一逻辑操作的重试间稳定，但模拟工具本身没有跨进程副作用幂等组件。
- 首个有效审批决定生效；并发提交可能都得到 202，只有最终工作流状态表示采用的决定。
- 本机审批不具备身份认证、审批人授权、参数指纹或动态策略；正式治理接入在 P1。
- API、Temporal gRPC 与 UI 仅绑定 loopback；当前 Compose 是开发演示配置。
- 取消不会回滚已经完成的外部操作。
- 当前没有业务查询库、LLM/RAG、管理前端、SSE、Sandbox 或多 Runtime；这些在 TODO 分阶段列明。
- 原始平台规划描述最终愿景，不是当前能力清单。

## License

Apache-2.0，见 [LICENSE](LICENSE)。
