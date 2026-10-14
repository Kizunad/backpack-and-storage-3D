# 安装与构建

适用：未发布的 `0.1.0-SNAPSHOT`。Java 17、Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1、owo 0.11.2+1.20。

先构建含统一服务端路由的 Inventory UI Framework；通过显式源码路径引用其两端 JAR，没有已发布的 Maven 坐标：

```sh
./gradlew -Dorg.gradle.java.home="/path/to/jdk17" \
  -PframeworkDir="/path/to/framework" test build
```

Windows 可改用 `gradlew.bat`，参数含路径时保留双引号。

省略 `frameworkDir` 时默认读取同级 `Kizuna-Inventory-UI-Framework` 的构建产物；不会下载或改写另一个源码副本。

2026-10-14 配套源码提交为 [Framework b2f6e30](https://github.com/Kizunad/Kizuna-Inventory-UI-Framework/commit/b2f6e3021c40f052702d48301408b333b3ed378f)。复现本次构建时，在 Framework 仓库检出该提交并先运行 `./gradlew test build`，再构建本仓库。它包含统一逻辑服务端、单机注册和本页所需 UI 接口；尚无正式 Release。

当前源码的背包页面实现 Framework 的 `VanillaHudHidden` 标记：打开页面时隐藏原版物品栏、准星及其余原版 HUD，退出后按原游戏设置恢复。构建和运行需使用包含该接口的新 Framework 客户端 JAR；旧的同名 `0.1.0-SNAPSHOT` JAR 不包含此接口。最小化容器只留下独立卡片，提供恢复和关闭两个按钮，关闭会走原有的取消转移与合盖流程。

| 安装位置 | 所需文件 |
|---|---|
| 客户端 | Framework 客户端、Fabric API、owo、`backpack-storage-3d-client-0.1.0-SNAPSHOT.jar` |
| 专用服务器 | Framework 服务端、Fabric API、`backpack-storage-3d-server-0.1.0-SNAPSHOT.jar` |

Framework 提供公共生成类和 Protobuf 运行库，业务 JAR 不重复打包它们。客户端和服务端需要使用同一次构建的协议版本。

2026-10-14 源码起，单机使用上表客户端组合；客户端产物已包含逻辑服务器和事务保存，不另装专用服务端 JAR。需要含内置路由的新 Framework，旧同名 SNAPSHOT 不具备此能力。房主业务库存以标准 playerdata 为准，避免较旧 level.dat 覆盖已经提交的库存；首次安装且文件没有业务字段时保留原版加载。

客户端首次启动生成 `config/bs3d/config.toml` 和默认 `.bbmodel`，后续不覆盖。自定义模型、开合片段和 OGG 须按[模型资源导入](Model-Resources.md)登记；F3+T 重载。资源仅安装在客户端，服务端通过稳定声音事件触发地面放置、收回音效，因此这一版本仍需同时更新两端 JAR。TOML 解析依赖已嵌入客户端 JAR，无需额外安装。

当前业务协议为 v6，增加地面放置、打开和收回，保留 v5 的世界装备外观同步。升级时同时替换两端业务 JAR 并重启；v4/v5/v6 不混用。玩家保存格式保持 3，新增地面目录格式 1，已有物品与挂点无需转换。装备容器后，按 F5 查看角色身上的外观。

手持容器 Shift＋右键放到地面，右键地面模型打开内景，关闭内景后空手 Shift＋右键收回。背包内容跟随同一件实物；其他附近玩家也可打开无人使用的地面背包。世界备份应包含 `data/bs3d/`、区块和 playerdata，地面事务与玩家库存的恢复关系见[通信和持久化](Protocol.md)。

默认按 B 打开，也可从 Framework 的“3D 背包”入口进入。生产库存初始为空，先在主手或副手拿起原版物品，再从角色模型的手掌拖到容器内景的格位；取出时反向拖到任意一只手。

草包、背篓和收纳箱均为真实容器物品。新玩家没有预置背包，可从创造模式工具分类取出，或由管理员使用 `/give @s bs3d:grass_pouch`、`/give @s bs3d:back_basket`、`/give @s bs3d:storage_box` 获取。双手与盔甲可直接拖放，容器可收纳其他容器；持有容器时右键手掌打开。当前源码已包含单机内置服务器适配；多人同时编辑同一仓库或其他模组旧库存迁移尚未实现。此模组使用独立模块 `bs3d:inventory` 和独立存档字段。

继续阅读[操作说明](Interaction.md)或[验证记录](Validation.md)。


`scripts/smoke.py` 是自动验收，不是手动演示入口：它会在隔离世界中生成测试物品、模拟拖放及取消，完成后退出。这些行为只在 smoke 源集和启动参数启用时存在，生产 JAR 不包含夹具。Linux 默认使用 Xvfb 隐藏窗口；显式传 `--show-window` 才显示自动操作。手动使用请安装上表中的正常 JAR，打开草包只播放开盖动画。
