# Contributing

请先阅读 `AGENTS.md`、`docs/architecture.md` 和 `TODO.md`。

行为变更先写失败测试，然后做最小实现。工作流测试优先使用 Temporal 测试服务；真实容器或模型联调必须单独标记。

首次构建或 AgentPermit 锁定更新后，先执行 `python scripts/bootstrap-agentpermit.py`。固定源码和校验和位于 `.mvn/agentpermit.lock.json`；不要从其他 checkout 安装同名依赖来代替此步骤。

提交前执行 Maven `verify` 与 `git diff --check`；容器修改还需 `docker compose config --quiet`，执行路径变化需运行 `python scripts/smoke.py`。

Compose 校验/启动与 smoke 需要 `PLATFORM_DATABASE_PASSWORD`，并与已有审批数据库卷保持一致。
CI 使用临时数据库口令，并执行 `python scripts/smoke.py --restart-approval-db`；此选项会重启本项目的审批数据库容器。

PR 描述包含具体行为、验收结果与尚未覆盖的边界。不得提交凭据、真实业务数据或日志。
