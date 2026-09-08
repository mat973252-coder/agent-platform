# 持久化执行骨架架构

## 范围

当前实现单个诊断任务的可恢复执行流程，使用固定合成证据和数据库测试账本。
P1.1 加入 AgentPermit4j 适配，P1.2 加入独立持久审批和本机账户身份验证，P1.3 加入持久执行权、结果复用和未知结果核验。测试账本具有真实数据库写入，但不重启真实服务；生产身份源、真实模型和生产业务工具仍在后续阶段，见根目录 TODO。

## 模块

```text
platform-api (Spring Boot REST + Worker 装配)
       ├─ durable-execution (Temporal Workflow / Activity 接口)
       └─ agentpermit-adapter (AgentPermit 管线 / JDBC 审批 / 本机模拟工具)
                   ↓
       agent-runtime-core (纯 Java 输入、状态、结果和快照)
```

durable-execution 与 agentpermit-adapter 都依赖 core，彼此不依赖；由 API 层装配。AgentPermit 类型不进入 core 或 Workflow。

Workflow 与 Activity 位于应用 Worker；Temporal Server 是独立服务。
Temporal 使用 PostgreSQL 保存内部执行数据，应用不得直接修改内部表。
审批、执行记录与受控测试账本使用独立 PostgreSQL 数据库和 Flyway 迁移，只访问业务表。未来 Run/Step 查询视图也不承担执行调度职责。

## 首个流程

```text
创建 Run → 查询合成证据 → AgentPermit Activity
                         ├─ EXECUTED → SUCCEEDED
                         ├─ DENIED → DENIED
                         ├─ FAILED → FAILED
                         └─ APPROVAL_REQUIRED → WAITING_APPROVAL
                              ├─ 批准 → 重新经过管线 → 测试账本操作/拒绝/待核验
                              ├─ 拒绝 → REJECTED
                              ├─ 等待超时 → TIMED_OUT
                              └─ 取消 → CANCELLED
读取/准备 Activity 重试耗尽 → FAILED
执行结果未知或执行 Activity 重试耗尽 → RECONCILIATION_REQUIRED
    ├─ 下游回执确认 → SUCCEEDED
    ├─ 无回执 → 继续待核验（不重做工具）
    └─ operator 留痕关闭 → CLOSED_UNKNOWN
```

API 接受调用方 request ID，并映射为稳定 Workflow ID。重复创建返回冲突。
审批只对当前 Run 的固定操作有效，先到的有效决定生效。
默认固定重启策略要求审批；允许、策略拒绝和失败通过 fixture 验收，不由外部请求指定。
AgentPermit 的工具名、主体、租户、环境和资源范围由服务端构造；API 不接收调用方声明的身份或授权结果。

新工作流先在 Activity 中幂等创建审批记录，绑定 Run、Step、操作 ID、工具、资源、AgentPermit 规范化调用指纹和 Workflow 计算的固定截止时间。重试校验全部绑定，不创建新审批单或延长过期时间。Workflow 不直接访问数据库或系统时钟。

API 使用显式配置的本机 Basic 账户：operator 创建/查询/取消/核验/关闭未知任务，approver 查询/审批，均只能操作固定 orders 演示范围。身份来自认证主体，不来自请求字段。API 保存决定时检查当前权限，首次执行时 AgentPermit 再验证原始指纹、当前审批人权限、策略、资源白名单和有效期。读取已有绑定的结果不再调用工具，无需重新获取写权限。生产身份源、细粒度多租户权限、TLS 及动态权限管理仍在 P5。

决定和待投递标记在同一行 UPDATE 内原子保存。`ApprovalDelivery` 重投未完成的通知；Temporal Signal 成功后按相同状态确认投递，旧 APPROVE 不能确认后来的 CANCELLED。Workflow 将 signal 视为通知，并通过 Activity 查询独立审批记录；伪造、重复和乱序 signal 不成为授权。审批列表本身是可重复读取的持久通知入口，未接邮件/IM。

取消与执行前 claim 使用互斥的条件 UPDATE。取消先获胜则后续 claim 失败且零工具调用；claim 先获胜则 API 不能再接受可撤销的取消。P1.3 将审批首次消费与执行记录插入放在同一事务；取消单独记录操作人和时间，保留原批准审计身份。数据库过期校验使用 Activity/API 时钟，部署需保证它与 Temporal 时钟同步。

`LocalDemoGovernance` 仅保留给 P0/P1.1 兼容 Activity：仍按旧流程决定重建内存审批，只执行合成模拟工具。旧版等待中的 Run 无可信审批记录，升级后应取消并以新 requestId 重建；不把旧批准信号补造为可信记录。

`reasonCode` 暴露最近一次工具治理或核验原因。管线拒绝作为终态返回；执行者获得执行权后的失败保守记为未知，不推断副作用未发生。Activity 本身抛出的错误仍按 Temporal 有限重试策略处理，但持久 guard 不允许再次调用工具。批准后若管线仍要求审批，本轮以 FAILED 结束，不自动生成无限审批循环。

`agentpermit-action-v1`、`persistent-approval-v1` 和 `durable-result-v1` 版本标记保留 P0/P1.1/P1.2 历史的命令序列。旧 `executeAction`、`attemptAction`、`executeApprovedAction` Activity 继续注册，P1.2 的模拟执行不升级为新的账本操作；不得在现有历史保留期内随意删除。6 份升级前生成的固定历史保护回放兼容性。

## 持久执行与未知结果

`JdbcResultIdempotencyGuard` 实现 AgentPermit 的审批感知 guard SPI。业务表对 operation ID 和 approval ID 分别设唯一约束；插入 `IN_PROGRESS` 与审批 `execution_started=FALSE` 的首次消费共用一个事务和 DataSource。只有插入成功的执行者调用 executor，不使用租约或超时接管。既有记录必须匹配原审批 ID 和规范化调用指纹，之后只能复用结果或返回待核验状态。

受控下游 `JdbcRestartLedger` 在另一个事务内锁定固定服务行、递增 generation、保存以 operation ID 为唯一键的回执。相同操作重复请求返回原回执；指纹或资源不一致则拒绝。执行记录完成更新是第三个提交边界，因此允许真实出现“账本已提交、执行结果尚未保存/上报”的间隙。数据库进程相同不代表这些事务原子合并。

Workflow 对未知结果立即执行只读 `reconcileAction`；有匹配回执则保存确认结果，否则保持 `RECONCILIATION_REQUIRED`。若执行记录尚不存在，核验先写入 UNKNOWN 阻止迟到 Activity 获得执行权。operator 可重新 CHECK 或带原因 CLOSE；决定与递增投递版本同事务保存，投递器只确认已发送的版本，Signal 仅唤醒下一次读取。`CLOSED_UNKNOWN` 为人工终态，保留身份与原因；迟到完成或后续回执不会覆盖它。它不证明副作用不存在，原执行者甚至可能仍在完成操作。

幂等保证依赖持久业务数据库、不可复用的逻辑 ID 和下游唯一回执契约。当前只验证这个测试账本，不推广为任意第三方写接口的无条件 exactly-once。故障注入 `FAIL_BEFORE_LEDGER_WRITE`、`FAIL_AFTER_LEDGER_COMMIT`、`PAUSE_AFTER_LEDGER_COMMIT` 只通过服务启动配置启用，默认 NONE。

重启动作在当前工具定义中是 IRREVERSIBLE，不提供逆操作。补偿契约：未来可补偿工具必须以 `<original-operation-id>:compensate:<step>` 使用独立稳定 ID（不含 attempt），绑定原操作和单独审批；前置条件须确认原结果、可逆性和当前资源状态。补偿失败或未知应分别记录，禁止宣称原操作已回滚。此处仅定义契约，当前没有可补偿工具或补偿执行器。

## 必须保持的约束

1. Workflow 代码确定性执行；网络、数据库、模型和工具调用放入 Activity。
2. Temporal 历史保存已记录的 Activity 结果；Worker 重启不重新请求这些结果。
3. Activity 可能执行多次。相同逻辑操作的 ID 必须跨 Attempt 稳定。
4. 新流程使用持久 guard 和受控账本；旧兼容 Activity 的内存 guard 不具有跨进程副作用保证。
5. 取消需要执行端配合，不等于回滚已完成的操作。
6. 等待超时、拒绝和失败必须有明确终态；不能为让演示成功静默放行。
7. API 默认本机监听；写请求要求角色和自定义请求头，不开放 CORS，不接收任意工具、脚本、URL 或资源路径。
8. 历史回放验证恢复兼容性；Eval 是另一次受控模型实验。

## 技术版本与验证

Java 21；Spring Boot 4.0.8；Temporal BOM/SDK/testing 1.38.0。
具体版本以根 POM 为准；本轮构建与运行结果在 TODO 中维护。
AgentPermit 使用 `.mvn/agentpermit.lock.json` 固定公开源码和 SHA-256，并显式构建到项目隔离的 Maven 仓库。
测试使用 Temporal TestWorkflowEnvironment；跨进程恢复单独用 PostgreSQL-backed Temporal 验证。

## 参考

- [Temporal Workflow](https://docs.temporal.io/workflow-definition)
- [Activity 幂等与重试](https://docs.temporal.io/activity-definition)
- [审批等待](https://docs.temporal.io/design-patterns/approval)
- [Java 测试与回放](https://docs.temporal.io/develop/java/best-practices/testing-suite)
- [官方 PostgreSQL Compose](https://github.com/temporalio/samples-server/tree/main/compose)
