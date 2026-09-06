# Production Agent Platform 开源项目：推荐技术栈与核心功能

> 目标：做一个可运行、可治理、可恢复、可观测的生产级 Agent Platform Demo。项目重点不是“再造一个 Agent Framework”，而是解决 Agent 从 Demo 进入生产后的 Runtime、Context、Tool、Policy、Durable Execution、Sandbox、Observability 与 Platform 管理问题。

## 1. 推荐技术栈

| 层级 | 推荐技术 | 用途 |
|---|---|---|
| Management UI | React 19 + Vite + TypeScript + TanStack Router + TanStack Query + Ant Design | Agent、Tool、Policy、Run、Approval、Eval 管理界面 |
| Agent Runtime / Control Plane | Java 21 + Spring Boot 3 + Spring AI + Spring Security | Agent 执行内核、平台 API、权限、MCP 接入 |
| Agent Protocol | MCP + HTTP/SSE；A2A 后续接入 | Tool、Resource、Agent 间标准化连接 |
| Durable Execution | Temporal Java SDK | 长任务、状态持久化、中断恢复、重试、人工审批 |
| Persistence | PostgreSQL + Redis | 配置、Run、事件、策略、审批、缓存与短期状态 |
| Context / RAG | Qdrant（或已有 Milvus）+ Embedding/Reranker | 检索、Evidence、Memory、Context Assembly |
| Policy / Identity | Spring Security + JWT/OIDC + 内置 Policy Engine | 用户/Agent 身份、RBAC/ABAC、工具授权、风险控制 |
| Sandbox | Docker Runtime；后续可接 gVisor | 不可信脚本/代码的隔离执行 |
| Observability | OpenTelemetry + Prometheus + Grafana + Tempo | Trace、Metric、Latency、Token、Cost、Tool Call 观测 |
| Eval / Replay | 自研 Java Eval Harness + 固定回放集 | 回归测试、版本对比、失败定位、结果回放 |
| Deployment | Docker Compose → Kubernetes + Helm | 本地一键启动，后续生产化部署 |
| Data Plane（后续） | AgentGateway | LLM / MCP / A2A 统一代理、路由与数据面治理 |
| Multi-runtime（后续） | Python Agent Adapter | 验证平台与具体 Agent Framework 解耦 |
| K8s Control Plane（后续） | Go Controller | AgentDefinition CRD、部署和状态同步 |
| Rust | 暂不自研 | 等出现明确 Gateway / Proxy 性能需求再投入 |

### 技术选择原则

- **Java 是主语言**：Runtime、Control Plane、Policy、业务 Tool 都优先 Java。
- **React 只服务管理台**：不在前端投入过多自研 UI 成本。
- **Python 只做多 Runtime 验证**：不把项目核心迁移到 Python。
- **Go 只在 K8s Controller 场景引入**。
- **Rust 暂不进入主线**：先集成 AgentGateway，不重复造 Data Plane。
- **先模块化单体，再按真实边界拆服务**。

---

## 2. 核心架构

```text
┌─────────────────────────────────────────────┐
│                Management UI                │
│ React + TypeScript                          │
│ Agent / Tool / Policy / Run / Eval Console  │
└──────────────────────┬──────────────────────┘
                       │ HTTP / SSE
                       ▼
┌─────────────────────────────────────────────┐
│         Java Agent Platform / Runtime        │
│                                             │
│ Runtime · Context · Tool · Policy            │
│ Durable Execution · Registry · Eval          │
└───────┬──────────┬──────────┬───────────────┘
        │          │          │
        ▼          ▼          ▼
    Temporal     MCP/Tool    Context/RAG
        │          │          │
        ▼          ▼          ▼
    Sandbox      Services   Vector Store
        │
        ▼
 OpenTelemetry / Metrics / Trace
```

---

## 3. 核心功能

### 3.1 Agent Runtime

负责一个 Agent Run 从创建到结束的完整执行生命周期。

核心功能：

- Run / Step / Attempt 数据模型
- Planner → Executor 执行循环
- LLM Step / Tool Step / Retrieval Step / Approval Step
- 状态机管理
- SSE 流式输出
- 最大步骤数、Token Budget、时间预算
- Timeout / Cancel
- Structured Output 与 Schema 校验
- Tool Error 分类
- 执行事件持久化

建议状态：

```text
CREATED
RUNNING
WAITING_TOOL
WAITING_APPROVAL
SUCCEEDED
FAILED
CANCELLED
TIMED_OUT
```

---

### 3.2 Context Runtime

负责“每一次模型调用到底应该看到什么”。

核心功能：

- System / User / Conversation / Working Context 分层
- Evidence 检索与来源追踪
- Tool Result 纳入上下文
- Context Token Budget
- 自动裁剪与 Compaction
- 长对话摘要
- Retrieval 去重与重排
- Context Snapshot
- Memory 接口
- 权限过滤
- Prompt Injection 内容标记与隔离

统一输出 `ContextPackage`：

```json
{
  "instructions": [],
  "conversation": [],
  "evidence": [],
  "toolResults": [],
  "memory": [],
  "budgetUsage": {},
  "provenance": []
}
```

---

### 3.3 Tool / MCP Runtime

把 Tool 从 Agent Framework 内部能力提升为平台公共能力。

核心功能：

- Tool Registry
- MCP Client / Server
- Tool Discovery
- JSON Schema 参数校验
- Tool Version
- Tool Health
- Timeout / Retry
- Circuit Breaker
- Idempotency Key
- Side Effect 分类
- Tool Result 标准化
- Tool 调用审计

Demo Tool：

```text
logs.search
metrics.query
code.search
k8s.getDeployment
k8s.restartDeployment
config.update
```

---

### 3.4 Policy / Identity / Approval

负责回答：

> 谁通过哪个 Agent，可以在什么上下文下，对什么资源执行什么动作？

核心功能：

- User Identity
- Agent Identity
- JWT / OIDC
- RBAC
- ABAC
- Tool Risk Level
- Resource Scope
- Allow / Deny / Require Approval
- 高风险 Tool 人工审批
- 最小权限
- Policy Decision Reason
- 全量 Audit Log
- Deny by Default

统一授权模型：

```text
Principal
  + Agent
  + Tool
  + Action
  + Resource
  + Environment
  + Context
  -> Policy Decision
```

---

### 3.5 Durable Execution

负责 Agent 长任务的可靠执行，而不是把可靠性完全交给 Agent Loop。

核心功能：

- Temporal Workflow
- Workflow / Activity 分离
- Event History
- Retry Policy
- Timeout
- Heartbeat
- Pause / Resume
- Human-in-the-loop
- Worker 重启恢复
- 用户 Cancel
- Idempotent Activity
- Compensation / Rollback
- 失败后重新规划

关键 Demo：

```text
Agent 执行
→ 查询数据
→ 生成处置计划
→ 等待人工审批
→ Worker 被关闭
→ Worker 重启
→ 从正确状态恢复
→ 执行高风险操作
→ 验证结果
```

---

### 3.6 Sandbox

负责隔离 Agent 生成或调用的不可信代码/脚本。

核心功能：

- Ephemeral Workspace
- Read-only RootFS
- CPU / Memory / PID 限制
- Execution Timeout
- 默认禁止外网
- Network Allowlist
- 文件系统隔离
- Scoped Secret Injection
- 输入 Artifact 挂载
- 输出 Artifact 收集
- Sandbox 自动销毁
- 后续可切换 gVisor

---

### 3.7 Observability

让每次 Agent Run 都可以解释、定位和追踪。

统一 Trace：

```text
agent.run
├── agent.plan
├── context.retrieve
├── context.assemble
├── llm.chat
├── policy.check
├── tool.call
├── approval.wait
├── sandbox.execute
└── agent.verify
```

核心指标：

- Run Success Rate
- P50 / P95 Latency
- TTFT
- Token Usage
- Estimated Cost
- Tool Success Rate
- Tool Latency
- Replan Count
- Policy Deny Count
- Approval Wait Time
- Context Size
- Sandbox Failure
- Error Category

---

### 3.8 Eval / Replay

负责判断版本升级后 Agent 到底变好了还是变差了。

核心功能：

- 固定 Regression Dataset
- Run Replay
- Tool Result Mock / Replay
- Prompt Version 对比
- Model Version 对比
- Context Strategy 对比
- Tool Selection Accuracy
- Task Success Rate
- Latency / Token / Cost 对比
- Rule-based Eval
- 少量 LLM-as-Judge
- 失败 Case Drill-down

---

### 3.9 Platform / Registry

把 Runtime 能力提升成可管理的平台能力。

#### Agent Registry

```text
Agent Name
Version
Runtime
Model
Prompt Version
Tool Set
Policy Set
Context Strategy
Owner
Environment
Status
```

#### Tool Registry

```text
Tool Name
Version
MCP Server
Owner
Schema
Risk Level
Side Effect
Permission
Health
Latency
Invocation Count
```

#### Run Console

```text
Run Timeline
Context Snapshot
LLM Calls
Tool Calls
Policy Decisions
Approval
Trace
Cost
Replay
Evaluation
```

#### Policy Console

```text
Policy
Principal
Agent
Tool
Resource
Environment
Decision
Approval Rule
Audit
```

---

## 4. 最小完整 Demo

最终至少可以完整演示一次：

```text
用户：
“订单服务从下午两点开始大量出现 5xx，帮我排查并在必要时处理。”

Agent
  ↓
查询 Metrics
  ↓
查询 Logs
  ↓
检索 Runbook / Code
  ↓
Context Runtime 组装证据
  ↓
生成诊断结论
  ↓
准备调用 k8s.restartDeployment
  ↓
Policy 判断为高风险
  ↓
WAITING_APPROVAL
  ↓
人工批准
  ↓
Temporal 继续执行
  ↓
执行 Tool
  ↓
验证 Metrics
  ↓
生成最终报告
  ↓
OpenTelemetry 展示完整 Trace
  ↓
Eval / Replay 可复现本次执行
```

同时额外演示：

- Prompt Injection 无法绕过 Policy。
- 重复请求不会重复执行高风险副作用。
- Worker 中途停止后任务可以恢复。
- Sandbox 中的脚本不能访问未授权网络和文件。
- 切换 Prompt / Model / Context Strategy 后可以跑固定回归集比较结果。

---

## 5. 推荐仓库结构

```text
agent-platform/
├── platform-api/              # Spring Boot API / Control Plane
├── agent-runtime-core/        # Agent Runtime Kernel
├── context-runtime/           # Context / Memory / Retrieval
├── tool-runtime/              # Tool Registry / MCP
├── policy-engine/             # Auth / Policy / Approval / Audit
├── durable-execution/         # Temporal Integration
├── sandbox-runtime/           # Sandbox Abstraction
├── observability/             # OpenTelemetry / Metrics
├── eval-harness/              # Eval / Replay
├── demo-ops-agent/            # 故障诊断 Demo Agent
├── demo-mcp-servers/          # Logs / Metrics / K8s 等模拟 Tool
├── management-ui/             # React Management Console
├── deploy/
│   ├── docker-compose/
│   ├── helm/
│   └── k8s/
└── docs/
    ├── architecture/
    ├── adr/
    └── demo/
```

---

## 6. 项目边界

这个项目**不做一个新的 LangGraph / ADK / Spring AI**。

核心定位是：

> **Agent Framework 之下、模型与工具之上，为生产级 Agent 提供 Runtime、Context、Tool、Policy、Durable Execution、Sandbox、Observability 与 Eval 基础设施。**

第一版优先把核心闭环跑通；AgentGateway、Python Runtime、Go Controller、Kubernetes CRD 都作为后续扩展，而不是 MVP 前置依赖。
