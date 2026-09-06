# 持久化执行骨架架构

## 范围

当前实现单个诊断任务的可恢复执行流程，使用固定合成证据和模拟操作。
P1.1 已加入 AgentPermit4j 适配；独立可信审批、跨 Worker 副作用幂等、生产身份、真实模型和真实写工具仍在后续阶段，见根目录 TODO。

## 模块

```text
platform-api (Spring Boot REST + Worker 装配)
       ├─ durable-execution (Temporal Workflow / Activity 接口)
       └─ agentpermit-adapter (AgentPermit 管线 / 本机模拟工具)
                   ↓
       agent-runtime-core (纯 Java 输入、状态、结果和快照)
```

durable-execution 与 agentpermit-adapter 都依赖 core，彼此不依赖；由 API 层装配。AgentPermit 类型不进入 core 或 Workflow。

Workflow 与 Activity 位于应用 Worker；Temporal Server 是独立服务。
Temporal 使用 PostgreSQL 保存内部执行数据，应用不得直接修改内部表。
未来业务查询视图使用独立数据库/schema，不承担执行调度职责。

## 首个流程

```text
创建 Run → 查询合成证据 → AgentPermit Activity
                         ├─ EXECUTED → SUCCEEDED
                         ├─ DENIED → DENIED
                         ├─ FAILED → FAILED
                         └─ APPROVAL_REQUIRED → WAITING_APPROVAL
                              ├─ 批准 → 重新经过管线 → 模拟操作/拒绝/失败
                              ├─ 拒绝 → REJECTED
                              ├─ 等待超时 → TIMED_OUT
                              └─ 取消 → CANCELLED
Activity 重试耗尽 → FAILED
```

API 接受调用方 request ID，并映射为稳定 Workflow ID。重复创建返回冲突。
审批只对当前 Run 的固定操作有效，先到的有效决定生效。
默认固定重启策略要求审批；允许、策略拒绝和失败通过 fixture 验收，不由外部请求指定。
AgentPermit 的工具名、主体、租户、环境和资源范围由服务端构造；API 不接收调用方声明的身份或授权结果。

`LocalDemoGovernance` 明确只用于本机演示：根据 Workflow 记录的批准决定，在当前 Activity 内重建短期内存审批并交给 AgentPermit 验证。适配器测试验证实际指纹匹配和进程内结果复用；它不能替代持久审批、审批人授权或跨进程原始参数绑定。P1.2 会替换这部分装配。

`reasonCode` 暴露最近一次工具治理原因。管线返回的业务拒绝/失败作为 Run 终态返回，不自动重试；Activity 本身抛出的临时执行错误仍按 Temporal 重试策略处理。批准后若管线仍要求审批，本轮以 FAILED 结束，不自动生成无限审批循环。

`agentpermit-action-v1` 版本标记保留旧历史的命令序列。旧 `executeAction` Activity 继续注册并通过治理管线执行模拟操作；不得在现有历史保留期内随意删除。

## 必须保持的约束

1. Workflow 代码确定性执行；网络、数据库、模型和工具调用放入 Activity。
2. Temporal 历史保存已记录的 Activity 结果；Worker 重启不重新请求这些结果。
3. Activity 可能执行多次。相同逻辑操作的 ID 必须跨 Attempt 稳定。
4. 当前幂等 guard 为进程内实现；模拟工具没有真实外部副作用，不能据此推导跨进程幂等保证。
5. 取消需要执行端配合，不等于回滚已完成的操作。
6. 等待超时、拒绝和失败必须有明确终态；不能为让演示成功静默放行。
7. API 默认本机监听，不接收任意工具、脚本、URL 或资源路径。
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
