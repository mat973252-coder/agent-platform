# Agent Platform

[![verify](https://github.com/mat973252-coder/agent-platform/actions/workflows/verify.yml/badge.svg)](https://github.com/mat973252-coder/agent-platform/actions/workflows/verify.yml)

Java + Temporal 的持久化 Agent 执行基础项目。

**当前阶段：P1.2 持久审批已实现，验收记录见 TODO。** 固定诊断工作流的模拟重启通过真实 AgentPermit4j 管线；审批记录保存在独立 PostgreSQL，API 验证本机账户角色，决定落库后可靠投递给 Temporal。尚未接入真实模型、真实写工具、生产身份系统或跨 Worker 的副作用幂等。

## 技术与结构

- Java 21、Spring Boot 4.0.8、Temporal Java SDK 1.38.0。
- 本地 Temporal Server 1.31.0 使用 PostgreSQL 16 持久存储，Temporal UI 2.49.1。
- `agent-runtime-core`：纯 Java 领域数据。
- `durable-execution`：Workflow 与 Activity 契约。
- `agentpermit-adapter`：可信调用构造、AgentPermit 决策映射及 JDBC 审批存储。
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

项目测试覆盖领域校验、真实 AgentPermit 决策、审批绑定/过期/取消竞态、工作流分支、Activity 重试、消息重投、旧历史回放和 HTTP 身份权限。测试中的 Temporal 使用内存服务，审批库使用 H2；真实 PostgreSQL 联调独立验收。依赖自身的 113 项测试单独记录。

## 本地启动

先启动 Docker，设置数据库口令及两个不同的账户口令（账户口令至少 16 个字符），再运行。数据库已有数据卷时必须复用原口令；环境变量不写入仓库：

```powershell
$env:PLATFORM_DATABASE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '审批数据库口令' -AsSecureString)).Password
$env:PLATFORM_OPERATOR_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'operator 口令' -AsSecureString)).Password
$env:PLATFORM_APPROVER_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'approver 口令' -AsSecureString)).Password
docker compose up -d --wait --wait-timeout 180
java -jar platform-api/target/platform-api-0.1.0-SNAPSHOT.jar
```

- API：`http://127.0.0.1:9090`；健康检查：`/actuator/health`。
- Temporal UI：`http://127.0.0.1:8233`；gRPC：`127.0.0.1:7233`。
- 审批 PostgreSQL：`127.0.0.1:5434`，独立数据库 `agent_platform`、独立卷 `approval-data`；Flyway 自动迁移业务表，不读写 Temporal 内部表。
- Temporal 的 PostgreSQL 无宿主机端口，其 Compose 固定口令仅供本机合成演示使用。
- `operator` 可创建/查询/取消；`approver` 可查询/审批。用户名可通过 `platform.security.operator-username`、`platform.security.approver-username` 覆盖。未配置有效口令时启动失败，不启用默认账户口令。
- `docker compose down` 停止服务并保留命名卷；本项目启动脚本不自动删除数据库数据。
- 覆盖端口示例：`java -jar platform-api/target/platform-api-0.1.0-SNAPSHOT.jar --server.port=9092`。

## 提交并审批一个任务

```powershell
$operatorHeaders = @{
  Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("operator:$env:PLATFORM_OPERATOR_PASSWORD"))
  'X-Platform-Request' = 'true'
}
$approverHeaders = @{
  Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("approver:$env:PLATFORM_APPROVER_PASSWORD"))
  'X-Platform-Request' = 'true'
}
$body = @{
  requestId = 'demo-001'
  service = 'orders'
  approvalTimeoutSeconds = 300
} | ConvertTo-Json
$run = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:9090/api/runs -Headers $operatorHeaders -ContentType application/json -Body $body
$state = Invoke-RestMethod -Uri "http://127.0.0.1:9090$($run.statusUrl)" -Headers $operatorHeaders
$state
# 等 state 为 WAITING_APPROVAL 后，提交该 Run 返回的 approvalId。
$decision = @{ approvalId = $state.approvalId; decision = 'APPROVE' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:9090$($run.statusUrl)/approval" -Headers $approverHeaders -ContentType application/json -Body $decision
Invoke-RestMethod -Uri "http://127.0.0.1:9090$($run.statusUrl)" -Headers $operatorHeaders
```

改为 `REJECT` 可拒绝；`POST /api/runs/{runId}/cancel` 请求取消。

| API | 结果 |
|---|---|
| `POST /api/runs` | 202；返回稳定 runId 与状态地址 |
| `GET /api/runs/{runId}` | 当前状态、合成证据、审批 ID、操作 ID 与结果 |
| `GET /api/runs/{runId}/approval` | 原始绑定、指纹、过期时间、决定与审批/取消人 |
| `GET /api/runs/pending-approvals` | 最早到期的最多 100 个待审批记录，重复查询不创建新审批单 |
| `POST /api/runs/{runId}/approval` | 202 表示决定与投递意图已持久保存，最终状态通过 GET 确认 |
| `POST /api/runs/{runId}/cancel` | 202 表示取消已请求 |

非法输入为 400，未认证为 401，角色不允许或缺少写请求头为 403，未知 Run 为 404，重复 requestId、冲突/过期审批或不可取消操作为 409。`requestId` 最长 64 个字符，仅支持字母、数字、下划线和连字符；已完成任务的 ID 也不能复用。相同审批人重复提交同一有效决定返回 202；相反决定不能覆盖先前决定。

所有写请求发送 `X-Platform-Request: true`，服务不开放 CORS；启用浏览器前端时须重新设计 CSRF 边界。本机 Basic 账户只是当前身份集成，生产环境仍需 TLS、正式身份源和资源权限模型。

查询中的 `reasonCode` 表示最近一次工具治理决定的原因。策略拒绝为 `DENIED`，用户拒绝为 `REJECTED`。允许/拒绝/失败分支使用离线 fixture 验证；当前 API 的固定重启策略始终要求审批，不提供调用方切换策略的参数。

## 跨进程恢复验收

需要运行中的 Compose、匹配的 `PLATFORM_DATABASE_PASSWORD` 和 Python 3 标准库。脚本使用临时账户口令启动 API（默认 9091），先验证等待中的 Run 与审批记录跨 Worker 重启恢复；再关闭消息投递、保存审批、强制结束进程，重启后由 outbox 补投并继续同一 Run。随后检查拒绝、取消、超时和重复请求。结束时只停止脚本创建的应用进程。

```powershell
python scripts/smoke.py
# 同时重启本项目的 approval-db 容器，核验审批决定确实留在 PostgreSQL 卷中：
python scripts/smoke.py --restart-approval-db
```

日志写入忽略的 `var/smoke.log`。此验收与内存工作流测试分开，GitHub Actions 也会运行它。实际执行记录见 TODO。

2026-09-06 已在 [GitHub CI](https://github.com/mat973252-coder/agent-platform/actions/runs/34025622000) 通过 PostgreSQL + Temporal 的 Worker 强制终止/恢复验收，并验证拒绝、取消、超时和重复请求保护。本机 Windows 的 Docker 引擎启动故障使本地容器联调尚未完成；Windows 本地 Maven 测试已通过。

2026-09-07 的 [P1.1 CI](https://github.com/mat973252-coder/agent-platform/actions/runs/34049953194) 已验证全新环境构建固定 AgentPermit 源码，并再次通过治理接入后的相同恢复验收。

## 当前保证与限制

- Temporal 恢复已记录的执行结果；Activity 在完成结果尚未上报时仍可能重试。
- `operationId` 作为 AgentPermit 的幂等键，贯穿同一逻辑操作的重试；进程内可复用结果，跨 Worker/进程的副作用幂等尚未实现。
- 审批单唯一绑定 Run、Step、操作 ID、工具、资源、规范化调用指纹及固定截止时间；重试不延长有效期。指纹还包含服务端主体、租户、环境及参数。
- 首个有效决定由条件 UPDATE 保存；审批消息只唤醒 Workflow，执行仍从数据库验证决定、审批人当前权限、指纹和过期时间。服务端关闭 `platform.policy.restart-enabled` 或更换审批人后，旧批准不能执行。
- 审批记录中的决定与 `delivered` 标记构成事务性 outbox，投递为至少一次；失败自动重投、重复消息不增加本流程的逻辑执行。通知入口目前是持久审批列表，无邮件/IM 通知渠道。
- 取消在执行前的数据库 claim 边界之前获胜时撤销审批；执行已经开始时返回冲突，不能承诺回滚。取消另存操作人，保留原审批人记录。过期通过截止时间判定，数据库不靠定时任务把 `PENDING` 改写为 `EXPIRED`。
- `Workflow.getVersion` 保留 P0/P1.1 命令序列，4 份固定旧历史参与回放。旧 Activity 仍使用明确隔离的本机模拟治理。旧版尚未决定的 Run 没有可信审批记录，应取消并用新 requestId 重建；不会自动把旧信号升级为可信审批。
- API、Temporal gRPC 与 UI 仅绑定 loopback；当前 Compose 是开发演示配置。
- 取消不会回滚已经完成的外部操作。
- 当前业务库仅保存审批，不是完整 Run/Step 查询视图；LLM/RAG、管理前端、SSE、Sandbox 和多 Runtime 在 TODO 分阶段列明。
- 原始平台规划描述最终愿景，不是当前能力清单。

## License

Apache-2.0，见 [LICENSE](LICENSE)。
