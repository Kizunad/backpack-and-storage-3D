# 仓库协作约定

面向第三方使用的 3D 背包业务模组，复用 Inventory UI Framework 的主题与统一路由。

- 文档、交流和关键方法内注释使用简体中文；公开名称使用英文。
- UI 颜色取自主题，尺寸、时间与容量使用有单位的命名常量。
- 物品只来自服务器真实 ItemStack；客户端提交意图，不提交可创建库存的 NBT。
- 转移在同一服务器调用内校验、提交；任何校验失败均保留原库存。
- 原版与业务库存原子保存于同一玩家文件。保留未提交改动，不擅自提交、推送。
- 协议以 .proto 为准，扩展说明维护于 docs/wiki/，区分已实现与已验证。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
