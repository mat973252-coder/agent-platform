# 持久化执行骨架架构

## 范围

当前实现单个诊断任务的可恢复执行流程，使用固定合成证据和数据库测试账本。
P1.1 加入 AgentPermit4j 适配，P1.2 加入独立持久审批和本机账户身份验证，P1.3 加入持久执行权、结果复用和未知结果核验。测试账本具有真实数据库写入，但不重启真实服务；生产身份源和生产业务工具仍在后续阶段，见根目录 TODO。
P2 增加严格决策契约、有限 Agent 循环与持久预算，并通过 Spring AI 接入显式配置的 OpenAI-compatible Chat Completions 服务。默认保留离线 fixture；真实请求的 token 来自服务报告，cost 按冻结的参考价格估算。

## 模块

```text
platform-api (Spring Boot REST + Worker 装配)
       ├─ durable-execution (Temporal Workflow / Activity 接口)
       └─ agentpermit-adapter (AgentPermit 管线 / JDBC 审批 / 本机模拟工具)
                   ↓
       agent-runtime-core (纯 Java 输入、状态、结果和快照)
```

durable-execution 与 agentpermit-adapter 都依赖 core，彼此不依赖；由 API 层装配。AgentPermit 类型不进入 core 或 Workflow。`AgentActivities` 把规划、固定 runbook 读取和结果核验与原工具 Activity 契约分开；core 只保存 JDK 数据类型和决策范围校验。

Workflow 与 Activity 位于应用 Worker；Temporal Server 是独立服务。
Temporal 使用 PostgreSQL 保存内部执行数据，应用不得直接修改内部表。
审批、执行记录与受控测试账本使用独立 PostgreSQL 数据库和 Flyway 迁移，只访问业务表。未来 Run/Step 查询视图也不承担执行调度职责。

## 首个流程

下图为保留的 P1 流程，也是新 Agent 循环请求一次 restart 时复用的审批/执行子流程。

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

`agentpermit-action-v1`、`persistent-approval-v1`、`durable-result-v1`、`agent-loop-v1` 和 `run-budget-v1` 版本标记保留升级前历史的命令序列。旧 `executeAction`、`attemptAction`、`executeApprovedAction` 和 `plan` Activity 继续注册；旧 Run 不插入后来增加的账本、模型或预算调用。不得在现有历史保留期内随意删除兼容路径。12 份升级前生成的固定历史保护回放兼容性。

## P2 首批 Agent 循环

```text
读取合成证据与固定 runbook → plan Activity（严格决策）
    ├─ evidence.read → 证据或读取失败观察 → 下一次 plan
    ├─ ops.restart → 原持久审批/执行/未知核验流程
    │                    ├─ 已确认 → 下一次 plan
    │                    └─ 拒绝/取消/超时/人工关闭 → 对应终态
    ├─ ops.verify → 只读核对回执 → 下一次 plan
    ├─ FINISH → 写入后要求核验成功 → SUCCEEDED
    └─ NEED_CONTEXT → FAILED / CONTEXT_INSUFFICIENT
第六次决策仍未结束 → FAILED / STEP_LIMIT_EXCEEDED
```

`AgentDecision` 定义 schemaVersion、action、tool、arguments 和 message。`ModelDecisionCodec` 严格拒绝重复字段、尾随 JSON、额外字段、非字符串参数及超过长度的内容；core 再校验动作和当前服务范围，Workflow 在分派前重复校验。允许的工具仅为 evidence.read、ops.restart、ops.verify；调用方或模型不能提供 approval ID、operation ID、身份、策略开关或授权结论。

`DemoAgentActivities` 是明确标记的离线模型 fixture 实现，固定选择打包的 JSON 结果；它不调用 LLM，也不把字符串匹配称为真实推理。`orders-runbook-v1` 同时记录当前固定提示指令，合成证据仅作为数据。模型 Activity 输入携带稳定 `<run-id>:model:<step>` ID、已有证据、runbook、上一步工具观察和版本；输出被 Temporal Activity 历史记录，下一步才执行工具。没有把整个循环封装成一个可重试 Activity。

每个 Run 仍只有一个稳定的 `restart:1` 操作及一个独立审批；读步骤可以重复，已确认写操作不能再次请求。写结果未知时同步等待原 reconciliation 流程，模型不能继续规划或换键写入；原审批/执行表和操作 API 的单操作约束保持有效。只读失败可反馈给模型重新规划；策略拒绝、审批拒绝和未知写不作为允许重做的反馈。

核验 Activity 只检查原操作、审批指纹、服务及期待输出是否对应已提交账本回执，不重新获取写权限，不执行写工具。`FINISH` 在写入后必须以核验确认为前提。模型/核验后续失败不会清空先前写入结果，Run 的失败状态与 `/operation` 的成功记录可以同时存在，这不代表回滚。无写诊断可直接 FINISH；结论仍是模型 fixture 的文字，不作为真实服务健康证据。

默认上限为 6 次模型决策，每次 Activity 最多 3 次尝试；重试 decision ID 不变。模型失败、无效输出、上下文不足、读取失败、核验未确认和预算耗尽有独立原因码或观察。已完成 Activity 的历史回放不会再调用模型。

## Run 预算与模型计量

服务端 `RunBudget` 在创建时写入 Workflow 输入，新路径初始化独立业务预算表；限额和 Workflow 计算的 deadline 不可在恢复时修改。默认 6 步、900 秒、100000 token、1000000 microUSD。HTTP 创建请求不能覆盖这些限额。`GET /api/runs/{runId}/budget` 返回固定限额、deadline、已用/预留 token 与 cost、物理模型尝试次数和计量模式；初始化尚未提交或旧 Run 无预算记录时返回 404。

总时间包含上下文读取、模型、工具、审批等待及重试退避。各自主 Activity 的超时取原超时与剩余时间的较小值；审批过期时间也受总 deadline 限制，执行端数据库再次校验。时间耗尽返回 `TIMED_OUT / RUN_TIME_BUDGET_EXCEEDED`；下一次退避已无法放入剩余时间时允许提前以该原因结束。超时不能回滚已经开始的写入；未知写仍允许超时后只读核验或人工关闭，确认后不再开始新的模型/工具步骤，保留原回执。这是自主执行时限，不是未知结果人工处理的强制结束时间。

`BudgetedPlanningActivities` 使用 `<run-id>:model:<step>` 和上下文 SHA-256 绑定决策，attempt 单独记录。每次实际模型请求前，在锁定预算行的事务中预留上限；`used + reserved` 必须同时满足 token 与 cost 限额。成功响应严格解析后与用量结算、缓存结果同事务提交；后续 Worker 读取确认结果不再请求模型或扣费。并发迟到响应各自结算，首个确认的有效输出保持不变。无效输出仍计入使用量。

丢失响应、进程退出或无法确认结算时保留全部预留额度；同一 attempt 不重新获准，下一 attempt 必须另有足够预算。没有自动退还未知额度或按超时接管的入口。数据库落账但 Activity 结果未上报时重试复用缓存；模型响应尚未落账时可能再次请求，不能承诺供应商 exactly-once。

离线 `meteringMode=OFFLINE_SIMULATED`：每次预留 2048 输入 + 512 输出 token、5000 microUSD，成功 fixture 使用 800 + 128 token、1000 microUSD。这些数值是故障验收单位，不是实际 token 统计或供应商账单。服务启动参数 `platform.demo.model-failure-mode` 支持 `FAIL_AFTER_MODEL_RESPONSE`、`PAUSE_AFTER_MODEL_RESPONSE`、`FAIL_AFTER_BUDGET_COMMIT`，默认 `NONE`，API 不能启用故障注入。

## Spring AI 模型边界

`ModelProfile` 在 Run 创建时冻结 provider、base URL、model、promptVersion、pricingVersion、输入/输出额度和每百万 token 的 microUSD 单价；不保存 API Key。V4 迁移将完整 profile 写入预算业务表，恢复校验全部绑定。旧 Workflow 输入缺少 model 或旧预算行没有 profile 时保持 offline，部署启用真实模型不会把旧任务改成联网请求。API 不接收调用方指定的模型或端点。凭据由 Worker 环境提供，旧 Run 的端点与当前凭据配置不一致时明确失败，不把凭据发送到变化后的端点；已缓存决策仍可只读复用。

`SpringAiPlanner` 使用 Spring AI 2.0.1 `OpenAiChatModel` 非流式调用，不注册工具执行器，不使用自动 Agent/工具循环。系统指令固定为 `diagnostics-prompt-v2`，合成证据、runbook 和观察作为 JSON 用户数据提供，严格解析器和 Workflow 仍负责动作范围校验，所有写工具仍通过 AgentPermit。兼容服务须实现 Chat Completions、`max_tokens` 和完整 usage；未默认假设其支持供应商专用的 JSON schema、reasoning 或工具协议。

每次远端模型 Activity 的 StartToClose 上限为 45 秒，ScheduleToClose 上限为 150 秒，均受剩余 Run 时间限制；HTTP 最多 40 秒且在总 deadline 前留出结算时间。显式注入官方 OpenAI SDK 4.49.0 的 OkHttp transport（关闭连接重试），同时设 SDK `maxRetries=0`，避免一次预留产生隐藏重试；Spring AI 自带 transport 的连接重试默认值不用于本项目。每次请求结束关闭 client。SDK 异常及上游错误正文不写入工作流历史，仅映射固定错误类型。

远端计量模式为 `PROVIDER_USAGE_CONFIGURED_ESTIMATE`。发送前校验系统指令与上下文 UTF-8 字节数加 256 的保守本地输入额度，输出用 `max_tokens` 限制；预留 profile 的完整输入/输出额度及相应估算费用。响应必须提供有效 input/output/total usage，缺失或不一致不能按零用量结算，也不回退 fixture；非法 JSON 有有效 usage 时仍结算。服务报告用量超出 allowance 时保持预留并失败。字节估算不等于供应商 tokenizer，代理附加上下文、错误用量或不遵守输出上限的服务不能由客户端保证真实消费硬上限；这需要可信服务契约。单价包括其版本，缓存 token 按配置的普通输入价保守估算；不声称等于网关折扣、缓存价格或实际账单。

CI 使用本机 HTTP mock 验证协议、500/断连接不隐藏重试、usage 缺失、端点绑定及完整审批/核验流程，不调用付费模型。`scripts/smoke-model.py` 是显式真实服务验收入口；它复用本机 dotenv 或环境，只发送合成诊断数据，以 H2 和内存 Temporal 承载流程。真实模型请求与 PostgreSQL 跨进程恢复是两套独立证据，不混称为同一次生产联调。

模型/提示词/工具/runbook 版本随 Activity 输入持久记录，并在 `agent` 查询字段展示。版本升级必须保留旧内容和兼容分支，不能用同一版本覆盖 fixture。完整步骤查询视图和模型供应商适配后续实现。

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

Java 21；Spring Boot 4.0.8；Spring AI 2.0.1；Temporal BOM/SDK/testing 1.38.0。
具体版本以根 POM 为准；本轮构建与运行结果在 TODO 中维护。
AgentPermit 使用 `.mvn/agentpermit.lock.json` 固定公开源码和 SHA-256，并显式构建到项目隔离的 Maven 仓库。
测试使用 Temporal TestWorkflowEnvironment；跨进程恢复单独用 PostgreSQL-backed Temporal 验证。

## 参考

- [Temporal Workflow](https://docs.temporal.io/workflow-definition)
- [Activity 幂等与重试](https://docs.temporal.io/activity-definition)
- [审批等待](https://docs.temporal.io/design-patterns/approval)
- [Java 测试与回放](https://docs.temporal.io/develop/java/best-practices/testing-suite)
- [Spring AI 与 Spring Boot 兼容范围](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
- [官方 PostgreSQL Compose](https://github.com/temporalio/samples-server/tree/main/compose)
