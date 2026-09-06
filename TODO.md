# Agent Platform 实施清单

> 状态日期：2026-09-07。P0 已交付，本轮推进 P1.1 治理适配；P1.2/P1.3 保留独立验收。
> 技术栈：Java 21、Spring Boot 4、Temporal Java SDK、PostgreSQL。
> 原始完整平台规划保留在 `production-agent-platform-tech-stack-and-core-features.md`；本清单决定实际执行顺序。

## 完成标准与边界

- 每项行为变更先定义可执行验收，再实现最小代码。
- `[x]` 只表示已经实现并验证；设计、模拟和生产能力分别记录。
- 第一阶段演示工具使用固定合成数据，不调用模型、不操作真实服务。
- Temporal 负责工作流执行历史、等待、重试和恢复；业务查询库不另建一套执行调度状态机。
- AgentPermit4j 负责工具执行时的授权、审批绑定、幂等与审计；工作流等待审批不等于已经完成这些安全能力。
- API 与演示服务默认只绑定本机；生产身份认证完成前不得作为生产审批服务开放。
- 不以模型重跑代替 Temporal 历史回放，不宣称外部副作用无条件 exactly-once。

## P0：可运行骨架（本轮交付）

### P0.1 工程与文档

- [x] 明确范围、技术栈和模块边界。
- [x] 初始化 Git `main`、Maven Wrapper、Java 21 构建。
- [x] 建立 `agent-runtime-core`、`durable-execution`、`platform-api` 三个实际使用的模块。
- [x] 加入 README、架构文档、贡献规范、许可证和本地启动说明。
- [x] 配置编码、换行、忽略规则，避免产物、凭据和本机配置进入仓库。
- [x] GitHub Actions 执行 Maven `verify`，校验 Compose 配置。

验收：新环境使用 JDK 21、Python 3.9+ 和网络，先构建锁定的 AgentPermit 源码，再运行 Wrapper 验证；不依赖本机其他项目、用户全局 Maven 缓存或未发布 SNAPSHOT。

### P0.2 首个持久工作流

- [x] 定义不可变 Run 输入、审批决定、状态和查询快照。
- [x] 完成 `证据查询 → 等待审批 → 模拟执行 → 完成` 工作流。
- [x] 每次运行具有稳定 Workflow ID，工具逻辑操作 ID 在 Activity 重试间保持一致。
- [x] 批准后执行；拒绝和审批超时保持零工具执行。
- [x] 第一条有效审批决定生效，后续决定不能改变已经作出的选择。
- [x] Activity 有明确超时和有限重试；永久失败形成可解释终态。
- [x] 支持查询执行状态、请求取消。

验收：工作流测试覆盖批准、拒绝、超时、重复决定、读取重试、永久失败和取消；明确模拟工具不提供跨进程副作用幂等。

### P0.3 API 与本地运行

- [x] 提供创建 Run、查询 Run、提交审批、取消 Run 的 REST API。
- [x] 输入校验、重复 Run ID、未知 Run、无效审批状态返回明确 HTTP 状态。
- [x] 创建请求使用调用方稳定 request ID；重复创建不启动第二个工作流。
- [x] 提供 PostgreSQL 持久存储的 Temporal 本地 Compose 配置及 Temporal UI。
- [x] 提供 API 操作示例和兼容 Windows/Linux 的 Python smoke 脚本。
- [x] 实际验证 API 与工作流连接，区分内存测试与真实 Temporal 联调证据。
- [x] 实际验证 Worker 在等待审批时停止、重启后恢复同一个 Run。

验收：能按 README 启动并提交任务；批准、拒绝、取消的结果能被查询；重启验收使用持久 Temporal 服务，不把内存测试冒充跨进程恢复。

### P0.4 发布

- [x] 本地 Maven `verify`、`git diff --check`、Compose 配置检查通过。
- [x] 检查待提交内容，无凭据、私有业务数据、构建产物或本机绝对路径。
- [x] 创建 GitHub `agent-platform` 仓库，推送 `main`。
- [x] 核对远端提交与本地提交一致。
- [x] 核对首个 GitHub Actions 结果并修复本轮问题。
- [x] 更新本清单和 README 的实际能力及验证记录。

## P1：AgentPermit4j 正式接入与工具副作用

### P1.1 依赖和适配边界

- [x] 确定可复现的 AgentPermit4j 依赖来源：固定公开提交、归档 SHA-256 校验、项目隔离 Maven 仓库，禁止隐式依赖某台机器的 Maven 缓存。
- [x] 新增专用适配模块，Runtime core 保持不依赖 AgentPermit、Temporal 或 Spring。
- [x] 重启 Tool Activity 的执行全部通过 AgentPermit 管线，包括旧历史的兼容 Activity；当前工具仍为模拟操作。
- [x] 将 ALLOW、DENY、REQUIRE_APPROVAL、FAILED 映射为明确工作流分支。
- [x] 主体、租户、环境、工具定义和资源范围由服务端固定演示上下文构造，不接收调用方声明的授权上下文。

验收：用同一工具的允许、拒绝、待审批三种 fixture 证明调用没有绕过 AgentPermit；拒绝路径副作用为零。

当前边界：已接入真实 AgentPermit 管线和指纹校验。`LocalDemoGovernance` 根据 Workflow 的流程决定重建短期内存审批，幂等 guard 也仅在进程内生效。这不是生产身份或持久审批实现，不计为 P1.2/P1.3 完成。

### P1.2 审批契约

- [ ] 审批记录绑定具体 Run、Step、工具、规范化参数指纹、资源及过期时间。
- [ ] 审批请求创建和通知可安全重试，不产生重复审批单。
- [ ] 审批 API 校验审批人身份与权限；工作流消息不能替代真实授权。
- [ ] 处理拒绝、过期、重复批准、批准与取消同时发生的竞态。
- [ ] 恢复后重新验证授权、策略和资源前置条件；不能复用已经失效的审批。
- [ ] 明确审批记录与 Temporal 消息的可靠交付方式；消息发送失败可重投。

验收：变更已批准参数、资源或身份后零执行；重复/乱序消息不会增加副作用。

### P1.3 幂等与未知结果

- [ ] 定义逻辑 operation ID；同一操作所有 Activity Attempt 使用相同 ID。
- [ ] 验证跨 Worker 的结果复用与审批消费，不只检查单进程计数器。
- [ ] 使用真实测试账本或支持幂等的可控下游，记录可核对的业务结果。
- [ ] 注入“副作用已完成但结果未上报”的故障，验证不会换 ID 盲目重做。
- [ ] 未知结果进入待核验状态，并支持查询下游、确认结果或人工结束任务。
- [ ] 给补偿操作定义独立 ID、前置条件和失败状态，不把不可逆动作标为可回滚。

验收：并发重试、Worker 丢失及响应丢失都能说明副作用次数与最终状态；公开说明保证依赖和失效条件。

## P2：真实 Agent 执行循环

- [ ] 用独立模型 Activity 调用 Spring AI，Workflow 内不直接执行模型或网络请求。
- [ ] 模型输出使用明确 schema，工具名与参数必须经过服务端校验。
- [ ] 区分模型错误、工具错误、策略拒绝、上下文不足和预算耗尽。
- [ ] 实现有限步骤的 plan/execute/verify 循环，固定模型/提示词/工具版本。
- [ ] 增加最大步骤数、总时间、token/cost 预算；重试计入预算。
- [ ] 保存执行所需模型结果或持久引用，使历史恢复使用既有结果。
- [ ] 避免把整段黑盒 Agent 循环放入单一重试 Activity。
- [ ] 检索/上下文先使用固定 runbook；有真实需要再接向量库和 compaction。

验收：离线模型 fixture 覆盖成功、非法工具、超预算和失败后重新规划；真实模型联调单独记录，CI 不依赖付费 API。

## P3：平台数据与可观测性

- [ ] 建立业务 Run/Step/Attempt 查询视图，明确与 Temporal 历史的映射及修复方式。
- [ ] 设计事件 ID 与顺序、幂等写入和断线重连游标；SSE 仅负责传输。
- [ ] PostgreSQL 业务 schema 使用版本化迁移，不与 Temporal 内部表混用。
- [ ] 大结果/Artifact 通过引用存储，控制工作流历史和 payload 大小。
- [ ] 关联 Run、Activity、工具调用与审批的 trace ID。
- [ ] 记录成功率、耗时、重试次数、审批等待、工具错误和预算使用。
- [ ] 定义日志、审计、模型输入输出的脱敏及保留规则。
- [ ] 第一版管理台只做任务列表、详情时间线、审批与失败原因。

验收：单次 Run 的状态和证据可追溯；日志不包含秘密；断线后能补齐事件，观测系统故障不改变业务执行决定。

## P4：升级、故障与回归验证

- [ ] 固定历史样本进入 CI，使用 WorkflowReplayer 检查代码兼容性。
- [ ] 区分工作流代码版本、模型版本、提示词版本、工具版本和数据契约版本。
- [ ] 定义长任务跨版本部署策略，明确旧 Worker 的保留/退出条件。
- [ ] 测试 Activity 执行中停止 Worker、服务短暂不可用和数据库重启。
- [ ] 验证取消传播、心跳与资源清理；不能承诺撤销已经完成的外部动作。
- [ ] 定义工作流历史上限与 Continue-As-New 策略，保持业务 Run/operation ID 稳定。
- [ ] 单独建立 Eval 数据集；模型重跑评测不能执行真实写工具。
- [ ] 对重试策略、超时和并发做小规模负载验证后再确定运行参数。

验收：能重现并解释各类故障；CI 同时保护流程兼容性与业务行为，但不把回放通过等同于新模型质量提升。

## P5：生产化与后续平台模块

- [ ] API 身份认证、资源权限、审批权限、访问审计及请求限流。
- [ ] 密钥注入、连接加密、网络隔离与最小权限部署。
- [ ] Temporal/业务库的备份、恢复演练、保留策略和监控告警。
- [ ] Worker 扩缩容与任务队列划分，验证资源用量后再调优。
- [ ] 不可信代码出现后再接 Sandbox，验证文件、网络、CPU/内存及超时边界。
- [ ] 第二种实际运行时出现后再接 Python Adapter，验证平台契约与框架解耦。
- [ ] Agent/Tool Registry、完整 Context Runtime、Gateway 和 K8s Controller 按真实需求推进。

验收：各模块以具体场景和故障验收进入主线，不以空目录或配置清单计为完成。

## P0 验证记录（2026-09-06）

- 本地 `mvnw.cmd -B -ntp verify`：22 项测试通过（领域 8、工作流 9、HTTP API 5）。工作流测试使用内存 Temporal 服务；包含 Activity 重试和执行历史回放。
- `docker compose config --quiet` 与 `scripts/smoke.py` Python 语法检查通过。
- [GitHub 仓库](https://github.com/mat973252-coder/agent-platform) 已创建并推送 `main`；功能与部署修复提交为 `4fb211cd8248b514f33600ae004c1534eb4652b3`，已核对与远端一致。
- [GitHub Actions 验收](https://github.com/mat973252-coder/agent-platform/actions/runs/34025622000)：Ubuntu / JDK 21 下 Maven 22 项测试、Compose 校验、PostgreSQL + Temporal 启动与跨进程 smoke 全部通过。
- 恢复证据：`run-817bb01b-f20b-4af5-9c64-228db432bbd0` 在等待审批期间强制结束 Worker，进程 PID `3818 → 3940` 后查询快照一致；批准后完成模拟执行。拒绝、取消、审批超时和重复 Run 保护检查通过。
- 首轮 CI 暴露 Temporal 镜像要求动态配置文件存在的问题；已补齐文件和只读挂载，并由上述 CI 验证修复。
- 本机 Docker Desktop 引擎启动失败，因此本地容器联调尚未完成；真实容器恢复证据来自 GitHub CI，未将内存测试计为跨进程恢复。

## P1.1 验证记录（2026-09-07）

- 先补适配层与新增工作流分支测试，观察缺少适配类/结果类型/Activity 契约的预期编译失败，再实现对应代码。
- `python scripts/bootstrap-agentpermit.py`：从公开提交 `c33911c595e5718b144d2bdb939bb3e23e17c909` 构建 AgentPermit `0.2.0`，校验归档 SHA-256；上游所需模块的 113 项测试通过。
- `mvnw.cmd -B -ntp verify`：37 项项目测试通过（core 8、adapter 8、workflow 14、旧历史回放 2、HTTP API 5）。
- 旧历史样本取自 P0 提交 `2dc4cc3`，分别覆盖等待审批和已批准完成；保留原 Workflow ID 验证回放，Activity 不被重新执行。
- [P1.1 GitHub CI](https://github.com/mat973252-coder/agent-platform/actions/runs/34049953194) 全部通过，验收提交为 `f71b603e4969a752a2d3fac8f404fff7b2c775e9`。全新 Linux 环境从锁定源码构建依赖，完成上游测试、项目 37 项测试、Compose、PostgreSQL + Temporal 启动，以及带治理原因码的 Worker 强制退出/恢复 smoke；拒绝、取消、超时与重复请求检查通过。
- 本机 Docker 引擎仍不可用，未宣称已完成本地容器联调。P1.2 可信持久审批、P1.3 跨进程副作用幂等与未知结果处理仍未完成。
