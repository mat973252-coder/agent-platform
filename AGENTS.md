# Agent Platform 开发约定

- 使用简体中文沟通，代码和公开类型使用清晰英文命名。
- 改变领域边界或公开 API 前阅读 `docs/architecture.md`，按 `TODO.md` 的验收推进。
- Java 21，模块化单体；core 只依赖 JDK，模块依赖保持单向。
- 行为修改先补可执行测试，观察预期失败，再实现最小变更。
- Workflow 内禁止直接调用模型、网络、数据库、系统时钟或非确定性 API。
- Activity 可能重试，稳定的逻辑操作 ID 不能包含 attempt number。
- 不把本机模拟审批、模拟工具或内存测试描述为生产能力。
- 文档、构建配置修改不机械新增行为单元测试，但要执行相关验证。
- 保持类和方法简短，避免推测性抽象、空模块及无关改动。
- 不提交凭据、真实业务数据、日志、构建产物或本机绝对路径。
- 提交前运行 `./mvnw -B -ntp verify`（Windows 用 `mvnw.cmd`）、`git diff --check`。
- 修改容器配置后运行 `docker compose config --quiet`；实际联调与测试结果分开报告。
