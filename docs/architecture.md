# 持久化执行骨架架构

## 范围

第一阶段构建单个诊断任务的可恢复执行流程，使用固定合成证据和模拟操作。
生产身份、AgentPermit4j 适配、真实模型和真实写工具分阶段接入，见根目录 TODO。

## 模块

```text
platform-api (Spring Boot REST + Worker 装配 + 模拟工具)
       ↓
durable-execution (Temporal Workflow / Activity 接口)
       ↓
agent-runtime-core (纯 Java 不可变输入、状态、快照)
```

Workflow 与 Activity 位于应用 Worker；Temporal Server 是独立服务。
Temporal 使用 PostgreSQL 保存内部执行数据，应用不得直接修改内部表。
未来业务查询视图使用独立数据库/schema，不承担执行调度职责。

## 首个流程

```text
创建 Run → 查询合成证据 → WAITING_APPROVAL
                            ├─ 批准 → 模拟操作 → SUCCEEDED
                            ├─ 拒绝 → REJECTED
                            ├─ 等待超时 → TIMED_OUT
                            └─ 取消 → CANCELLED
Activity 重试耗尽 → FAILED
```

API 接受调用方 request ID，并映射为稳定 Workflow ID。重复创建返回冲突。
审批只对当前 Run 的固定操作有效，先到的有效决定生效。
首版审批为本机流程演示，不代表身份认证、动态策略或 AgentPermit 的安全审批。

## 必须保持的约束

1. Workflow 代码确定性执行；网络、数据库、模型和工具调用放入 Activity。
2. Temporal 历史保存已记录的 Activity 结果；Worker 重启不重新请求这些结果。
3. Activity 可能执行多次。相同逻辑操作的 ID 必须跨 Attempt 稳定。
4. 模拟工具没有真实外部副作用；不能从模拟成功推导出跨进程幂等保证。
5. 取消需要执行端配合，不等于回滚已完成的操作。
6. 等待超时、拒绝和失败必须有明确终态；不能为让演示成功静默放行。
7. API 默认本机监听，不接收任意工具、脚本、URL 或资源路径。
8. 历史回放验证恢复兼容性；Eval 是另一次受控模型实验。

## 技术版本与验证

Java 21；Spring Boot 4.0.8；Temporal BOM/SDK/testing 1.38.0。
具体版本以根 POM 为准；本轮构建与运行结果在 TODO 中维护。
测试使用 Temporal TestWorkflowEnvironment；跨进程恢复单独用 PostgreSQL-backed Temporal 验证。

## 参考

- [Temporal Workflow](https://docs.temporal.io/workflow-definition)
- [Activity 幂等与重试](https://docs.temporal.io/activity-definition)
- [审批等待](https://docs.temporal.io/design-patterns/approval)
- [Java 测试与回放](https://docs.temporal.io/develop/java/best-practices/testing-suite)
- [官方 PostgreSQL Compose](https://github.com/temporalio/samples-server/tree/main/compose)
