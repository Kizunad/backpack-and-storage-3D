# 模型、动画与音效导入

适用：`0.1.0-SNAPSHOT`，配置格式 **version = 1**。此功能已实现，模组尚未发布、Java API 尚未冻结。客户端使用本地白名单导入资源，不从网络接收文件路径。

## 目录与注册

客户端首次启动自动创建 `config/bs3d/config.toml`，并复制带有开合动画的草包、背篓模型。后续启动不覆盖已有配置和模型。每个游戏实例维护自己的目录：

```text
config/bs3d/
  config.toml
  models/
    GrassPouch.bbmodel
    BackBasket.bbmodel
    travel_bag.bbmodel
  sounds/
    travel_open.ogg
    travel_close.ogg
    travel_place.ogg
```

复制 `.bbmodel` 后，必须在 `config.toml` 添加 `[[models]]`。仅复制文件不会启用；未注册的 `.bbmodel` 会在 `logs/latest.log` 输出 WARN。删除注册并按 **F3+T** 后，模型、贴图和自定义声音从当前目录撤销，UI、手持、世界装备及地面渲染不会继续使用旧模型缓存。

完整格式如下。保留内置模型时，把新条目追加在已有配置之后；`version` 只在文件开头写一次。

```toml
version = 1

[[models]]
id = "example:travel_bag"
file = "models/travel_bag.bbmodel"
open_animation = "open"
close_animation = "close"

[models.sounds]
open = "sounds/travel_open.ogg"
close = "sounds/travel_close.ogg"
place = "sounds/travel_place.ogg"
# 可选：收回地面容器、转移开始和完成时接触容器。
# pickup = "sounds/travel_pickup.ogg"
# touch = "sounds/travel_touch.ogg"
```

| 字段 | 约束与默认行为 |
|---|---|
| `version` | 必填整数，目前只能为 `1` |
| `models` | `[[models]]` 数组；`models = []` 表示明确禁用全部导入模型 |
| `id` | 必填、唯一的 `namespace:path`，对应容器类型注册时的模型 ID，不是容器实例 UUID |
| `file` | 必填 `.bbmodel` 相对路径，路径统一使用 `/` |
| `open_animation` | 可省略，自动寻找 `open`；指定其他名称时必须存在；空字符串禁用该方向的片段选择 |
| `close_animation` | 可省略，自动寻找 `close`；规则同上 |
| `sounds` | 可省略的表，仅允许 `open`、`close`、`place`、`pickup`、`touch`，值为相对 `.ogg` 路径 |

所有路径都相对于 `config/bs3d`，禁止绝对路径或符号链接越出该目录。TOML 不接受未知字段、重复 ID、错误字段类型或版本；整个注册表无效时，日志报告原因并停用全部导入模型。单个已登记模型缺失或解析失败，仅跳过这个模型。可选声音失败则保留该动作的默认声。

配置只是资源登记，不创建物品、不改变容量、不修改装备规则。扩展模组仍需在两端物品注册阶段调用 `ContainerItems.register`，或通过 [Protobuf 物品清单](Item-Registry.md) 登记，将 `model` 设为同一个完整 ID；详见[物品扩展](Extension-Guide.md)。早期 `grass_pouch` 之类的 Java 短名称兼容为 `bs3d:grass_pouch`，TOML 必须写完整 ID。

## Blockbench 编辑约定

首版支持 **Blockbench 4.x `.bbmodel` 的 cube 模型**：逐面 UV、多张 PNG、面的 90 度 UV 旋转、隐藏面、立方体局部旋转及嵌套分组。推荐把 PNG 嵌入模型；外部 PNG 必须通过 `relative_path` 引用，相对于 `.bbmodel` 所在目录解析，且最终仍在 `config/bs3d` 内。编辑器保存的 `path` 绝对路径不被读取。

模型按 **16 像素 = 1 方块** 渲染，参考水平中心为 `(8, 8)`，Y 轴向上。保持与内置编辑源一致的坐标约定；腰部和胸背挂载继续由公共挂点变换控制。贴图原有 UV 不重绘，地面高度按关闭姿态的实际最低点计算。

几何文件最大 16 MiB、4096 个元素、32 层分组；每张 PNG 最大 8 MiB、4096×4096。`mesh`、box UV、`rescale`、`inflate` 和 Blockbench 5.x 当前不支持，会明确报告错误；不要把这类资源当成已支持模型导出格式。

作为物品渲染时，扩展模组还需提供 `assets/<物品命名空间>/models/item/<物品路径>.json`，其中 `parent` 为 `builtin/entity`，可通过标准 `display` 字段调整原版物品展示变换。未改用内置实体渲染的普通收纳箱保留标准物品模型。地面容器优先使用已登记的导入模型。

## 可选开合动画

在 Blockbench 中给盖子建立骨骼分组并设置枢轴，创建 `open`、`close` 两个动画，也可在 TOML 指定其他名称。片段时长读取 `.bbmodel` 的 `length`，单位为秒，支持 `(0, 60]`；内景窗口等待开启动画播放到位后出现。

- 支持骨骼 `position`、`rotation`、`scale` 三通道，数值常量关键帧，以及 `linear`、`step` 插值。
- 位置单位为模型像素，旋转单位为度，缩放为正倍率；关键帧时间必须处于片段时长内。
- 开合为单次动作，支持 `once` / `hold`。开启后保持末帧，关闭后保持关闭姿态。
- 两个动画都没有时使用静态模型，打开内景不额外等待；只提供一个方向时，另一方向反放该片段。
- 播放中途取消会沿当前片段反向回到起点；完全打开后关闭，才选择独立 `close` 片段。`open` 末帧应与 `close` 首帧一致，两个关闭端点也应一致。
- 不执行 Molang、脚本、效果轨道、循环、Bezier 或 Catmull-Rom。显式选择了不支持的片段时拒绝加载该模型，不静默近似动画。

动画仅为个人 UI 预览；世界里背在身上、手持和放到地面的容器显示关闭姿态。UI 开合不向旁观者广播。

## 可选 OGG 音效

音频须为 **Ogg Vorbis**，不是改后缀的 WAV 或 Ogg Opus；每个文件最大 8 MiB、最长 60 秒。推荐单声道短音效，地面放置和收回使用原版距离衰减。音频在导入时用游戏解码器校验，失败时输出包含模型 ID、动作和路径的 WARN。

`open` / `close` 用于实际开合，恢复已打开窗口不会重播开启声；`touch` 用于转移开始和完成时接触容器。物品材质碰撞声继续由服务端 item tag 规则选择，两套配置各司其职。

`place` / `pickup` 在服务器完成实际地面放置或收回后广播，附近玩家都能收到。两端按容器类型登记稳定的 `bs3d:container/<模型命名空间>/<模型路径>/<动作>` SoundEvent，客户端资源配置仅替换它的音频文件。未配置时使用默认的布料、木头或包裹声音。

服务端无需安装 `.bbmodel` 或 OGG，也不读取客户端 TOML。希望所有人看到、听到相同效果，需要给所有客户端分发相同目录；本功能不提供自动下载。新增声音事件要求客户端和服务端同时更新此版本 JAR，协议仍为 v6，保存格式不变。

## 可选复制工具与迁移

也可以用仓库辅助脚本复制单个模型。它不会修改注册表、不会覆盖目标文件，只输出待追加的 TOML；支持将模型目录内的相对 PNG 嵌入复制件。

```sh
python3 scripts/import_models.py "/path/to/travel_bag.bbmodel" \
  --id "example:travel_bag" --into "/path/to/game/config/bs3d"
```

旧脚本的“两份固定模型目录 → 构建期 JSON”模式已替换；运行时不再读取旧的 `assets/bs3d/models/<名称>.json`。`models/` 保留原始编辑源，带开合片段的首次启动模板位于客户端资源的 `bs3d/defaults/`。已有玩家库存、容器 UUID、装备和地面保存均无需迁移。

具体测试证据与限制见[验证状态](Validation.md)。
