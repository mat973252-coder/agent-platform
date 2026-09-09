# Contributing

请先阅读 `AGENTS.md`、`docs/architecture.md` 和 `TODO.md`。

行为变更先写失败测试，然后做最小实现。工作流测试优先使用 Temporal 测试服务；真实容器或模型联调必须单独标记。

首次构建或 AgentPermit 锁定更新后，先执行 `python scripts/bootstrap-agentpermit.py`。固定源码和校验和位于 `.mvn/agentpermit.lock.json`；不要从其他 checkout 安装同名依赖来代替此步骤。

提交前执行 Maven `verify` 与 `git diff --check`；容器修改还需 `docker compose config --quiet`，执行路径变化需运行 `python scripts/smoke.py`。

Compose 校验/启动与 smoke 需要 `PLATFORM_DATABASE_PASSWORD`，并与已有审批数据库卷保持一致。
CI 使用临时数据库口令，并执行 `python scripts/smoke.py --restart-approval-db`；此选项会重启本项目的审批数据库容器，并删除且重建本次 smoke 自建 UUID Run 的观测投影，不删除审批、预算、执行记录或账本。
smoke 需要相邻两个空闲端口，验证账本提交后强制终止 Worker、跨进程结果恢复及未知任务人工关闭。`platform.demo.failure-mode` 仅用于服务启动时注入测试故障，默认 `NONE`；不得作为业务 API 参数暴露。
预算 smoke 还在 `SYNTHETIC_MODEL_RESPONSE_PAUSED` 日志观测点之后终止进程，检查未确认预留保留和重试单独计量。`platform.demo.model-failure-mode` 同样只允许启动时配置，默认 `NONE`。合成 token/cost 不作为供应商计量证据；不得按超时自动退还未知请求的预留。

PR 描述包含具体行为、验收结果与尚未覆盖的边界。不得提交凭据、真实业务数据或日志。

离线 Agent 的 schema、严格解析器和 fixture 必须保持一致。修改模型、提示词、工具或 runbook 时显式升级版本；历史保留期内保留旧版本所需的 Activity/资源，不能在同一版本下静默换内容。CI 不调用付费模型 API。

Spring AI 默认测试使用本地 HTTP mock。真实调用只能通过显式 `scripts/smoke-model.py` 和本机凭据启用；不得给 CI 注入模型密钥。端点、模型、prompt、价格必须随 Run 冻结，SDK/transport 升级须复核 500 与断连接测试，防止隐藏重试绕过预留。报告分开列出服务 usage、配置费用估算、真实网络请求和 PostgreSQL 进程恢复证据。

观测投影不能进入 Workflow/Activity 执行依赖。新增事件字段必须使用明确白名单，不复制原始 payload 或异常；不能伪造 Temporal 未记录的中间重试。修改游标或投影时验证重复/并发刷新、缺行重建、客户端断线和执行绑定。内存 Temporal 不实现 Visibility List RPC，列表真实分页必须由容器 smoke 验证，不以返回 503 的本地测试替代成功验收。
