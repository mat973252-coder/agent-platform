# P0 / P1.1 历史兼容样本

样本由本项目提交 `2dc4cc3e5bbaf410c9e350518c883df2b6d9bdf1` 的
`DiagnosticsWorkflowImpl` 与内存 `TestWorkflowEnvironment` 生成。
运行输入为 `orders`、30 秒审批超时，Run ID 固定为 `run-p0-history-fixture`。

- `p0-waiting.json`：读取证据后等待审批的历史前缀。
- `p0-approved.json`：同一 Run 收到 APPROVE 并完成模拟操作的完整历史。

它们不含真实业务数据；Worker identity 已替换为合成值。测试只回放历史，不执行 Activity。
新增 AgentPermit Activity 前使用 `Workflow.getVersion` 保留旧命令序列；不得为了让回放通过重新生成这些旧样本。

`p1-waiting.json` 与 `p1-approved.json` 在 P1.2 Workflow 修改前，以提交
`05327b51d373feafe4e7a932255467dc7e0f3a24` 的 P1.1 实现生成。
Run ID 为 `run-p1-history-fixture`，输入同为 orders / 30 秒，Activity 使用合成 fixture；
Worker identity 已脱敏。这些样本验证新增持久审批命令不破坏 P1.1 历史。
