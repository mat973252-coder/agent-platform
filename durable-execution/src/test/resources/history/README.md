# P0 历史兼容样本

样本由本项目提交 `2dc4cc3e5bbaf410c9e350518c883df2b6d9bdf1` 的
`DiagnosticsWorkflowImpl` 与内存 `TestWorkflowEnvironment` 生成。
运行输入为 `orders`、30 秒审批超时，Run ID 固定为 `run-p0-history-fixture`。

- `p0-waiting.json`：读取证据后等待审批的历史前缀。
- `p0-approved.json`：同一 Run 收到 APPROVE 并完成模拟操作的完整历史。

它们不含真实业务数据；Worker identity 已替换为合成值。测试只回放历史，不执行 Activity。
新增 AgentPermit Activity 前使用 `Workflow.getVersion` 保留旧命令序列；不得为了让回放通过重新生成这些旧样本。
