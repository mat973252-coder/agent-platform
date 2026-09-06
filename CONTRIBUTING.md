# Contributing

请先阅读 `AGENTS.md`、`docs/architecture.md` 和 `TODO.md`。

行为变更先写失败测试，然后做最小实现。工作流测试优先使用 Temporal 测试服务；真实容器或模型联调必须单独标记。

提交前执行 Maven `verify` 与 `git diff --check`；容器修改还需 `docker compose config --quiet`，执行路径变化需运行 `python scripts/smoke.py`。

PR 描述包含具体行为、验收结果与尚未覆盖的边界。不得提交凭据、真实业务数据或日志。
