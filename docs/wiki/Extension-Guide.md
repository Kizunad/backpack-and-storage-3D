# 物品与声音扩展

适用：`0.1.0-SNAPSHOT`。真实 ItemStack 保留原版和模组的 NBT、模型、名称及附魔。缺少专用尺寸规则时按 `1×1×1` 格处理。

## 物品描述与附属字段

状态：2026-10-14 源码已实现并验证，尚无正式发布版本，API 未冻结。新容器注册签名为：

```java
ContainerItems.register(
        Identifier id,
        String title,
        String descriptionTranslationKey,
        String model,
        Size inside,
        Size outside,
        List<Mount> mounts,
        Map<Identifier, JsonObject> optional);
```

`descriptionTranslationKey` 是简介的语言键，空字符串表示无补充简介。扩展模组在 `assets/<命名空间>/lang/zh_cn.json`、`en_us.json` 等资源中提供文案。客户端会在原版物品 tooltip 与 BS3D 模型悬停 tooltip 中追加该描述，保留原物品名称、Lore、附魔及其他模组提示；拖放、相机拖动和帮助面板捕获期间不显示悬停提示。

`optional` 参数**必须显式传入**且不能为 `null`；没有附属配置时传 `Map.of()`。它是附属扩展字段表，键为带命名空间的 `Identifier`，值为 `JsonObject`。字段结构、单位、合法范围和回退由对应附属定义；BS3D 不解析重量或其他附属业务，也不要求安装对应附属。未安装的附属字段不会参与基础库存逻辑。

例如在注册阶段准备重量附属的单件自重（单位克），无需引用重量附属 Java 类：

```java
var weight = new JsonObject();
weight.addProperty("grams", 250);
var optional = Map.of(new Identifier("bs3d_weights", "weight"), weight);

ContainerItems.register(
        new Identifier("example", "pouch"),
        "示例草包",
        "item.example.pouch.description",
        "example:pouch",
        Volumes.size(1, 2, 2),
        Volumes.size(2, 2, 2),
        List.of(Mount.WAIST_RIGHT_FRONT),
        optional);
```

该片段仅展示当前源码注册入口，需要在两端初始化、注册表冻结前调用，并另行提供模型及语言资源。注册时复制整个字段对象，读取时也返回副本，注册方和附属不能通过修改 JSON 悄悄改写已有配置。

原版及第三方已注册物品通过 `ItemMetadata.register(Item item, String descriptionTranslationKey, Map<Identifier, JsonObject> optional)` 补充描述和附属字段，例如 `ItemMetadata.register(Items.IRON_SWORD, "item.example.iron_sword.description", optional)`。该入口不创建或替换真实物品；每个物品类型只允许登记一次，容器注册已自动登记，不要重复调用。查询入口为 `ItemMetadata.of(ItemStack)`，其 `descriptionTranslationKey()` 返回语言键，`optional(Identifier)` 返回 `Optional<JsonObject>`；未登记类型按无描述和空字段表处理。字段属于物品类型，不是容器 UUID，也不会写入物品 NBT 或由客户端请求上报。

重量附属在逻辑服务器匹配 `bs3d_weights:weight`：合法 `grams` 优先；缺失或无效字段记录 WARN 后回退到现有数据包规则，无匹配时使用 100 克。每个物品类型在一次成功规则加载周期中只警告一次。内置容器传空 `optional`，不在 BS3D 中硬编码附属业务重量，默认值仍由重量附属数据包提供。

迁移：旧的六参数 `ContainerItems.register` 已替换。第三方源码需要在 `title` 后补上描述键，并在最后补上 `optional`；无描述传 `""`，无附属配置传 `Map.of()`，重新编译后两端一起更新。当前没有静默省略 `optional` 的旧签名。此改动不改变业务协议 v6 或库存保存格式。

## 使用标准 item tag

在数据包中定义 `data/example/tags/items/metal_equipment.json`：

```json
{"replace": false, "values": ["minecraft:iron_sword", "minecraft:iron_pickaxe"]}
```

在 `data/example/bs3d/handling/metal_equipment.json` 注册规则：

```json
{
  "tag": "example:metal_equipment",
  "sound": "minecraft:block.chain.hit",
  "duration_ticks": 50,
  "size": [1, 2, 1],
  "priority": 100
}
```

`size` 顺序为宽、高、深，必须恰好包含三个数值，每维 1..3；省略时使用 `1×1×1`。`duration_ticks` 为 1..200 tick。声音必须是服务器注册表中存在的 SoundEvent。`priority` 默认 0，优先级越高越先匹配，相同优先级按资源 ID 排序。

规则先完整读取并校验，再整体替换当前目录。任一文件失败都会中止本次规则重载并保留旧目录；错误包含资源 ID 和出错字段，例如 `size.高` 或 `duration_ticks`。2026-10-13 的可读性整理同时明确了配置类型：数值字段使用 JSON 数值，不接受数字字符串；`size` 多余的轴也会报错，之前版本会忽略第三轴之后的值。合法配置不需要迁移。

原版 `/reload` 重新加载这些规则。新尺寸用于之后存入的物品，已存实例保留未旋转原始尺寸，放置时根据朝向置换轴，避免重载造成重叠或丢失。规则允许匹配第三方模组的物品 tag。

未命中规则时，方块使用该方块自己的放置音效；其他物品使用轻触声。内置 `bs3d:metal_tools` 将金属工具关联到链条碰撞声和两格高度。当前选择依据是被转移物品的材质，尚无两种不同材质的组合音效矩阵。

## 模型资产

客户端直接导入 `config/bs3d/config.toml` 登记的 `.bbmodel`，支持可选开合关键帧与五类自定义 OGG 音效。未登记文件不加载并输出 WARN；F3+T 同时重读注册表、模型、贴图和音效。固定格式、Blockbench 支持范围、默认行为和旧导入脚本迁移见[模型资源导入](Model-Resources.md)。

容器规格集中在 `ContainerItems`，使用上方带描述与 `optional` 的八参数入口同时注册真实不可堆叠物品及类型定义。两端必须在物品注册表冻结前注册相同类型；空挂点列表表示仅可收纳。当前 API 未冻结。新增模型还需提供对应客户端资源；类型的内部容量与外部占格不受普通 item tag 尺寸规则覆盖。UI 颜色来自 Framework 当前主题的语义令牌。

`ContainerItems.Definition.mounts()` 定义每类容器支持的挂点，UI 与世界玩家外观通过 `WornModelTransform` 共用 body 骨架的局部变换。新增挂点需要同步 protobuf 枚举、服务端合法列表与此变换；当前有腰环与胸背两类挂载，不能仅添加枚举而不实现变换。人物转动不改变持久化挂点；物品朝向使用共享 `OrthogonalRotation`，详见[协议](Protocol.md)。

世界外观由 `WorldEquipmentRenderer` 按服务器下发的注册物品 ID 选择资产，复用同一模型与纹理；未知或不可装备的类型跳过。环境光和受伤叠色由原版传入，F3+T 后重新解析世界模型。扩展方无需注册第二个网络通道；`bs3d:appearance` 经 Framework 统一路由，且只携带外观字段。UI 的开盖与鼠标命中留在 `WornPreview`，不向旁观者广播个人操作界面。

内景通过 Framework `UiWindowManager` 复用身份、置顶、拖动捕获、最小化及视口约束；各窗口持有独立相机和可见格层范围。范围使用容器 Y 轴的零起点半开区间，至少保留一层，仅存在于客户端窗口内，不修改协议、容量或存档。不要在子模块另起全局窗口路由。

切片对标准 `QUADS` 物品模型做几何裁剪，同时插值纹理、颜色和光照；旋转相机不改变切片所在的容器格层。使用其他图元或绕开原版顶点消费者的自定义物品渲染不在当前裁剪支持范围内。占用轮廓根据服务器快照中的实际朝向后尺寸合并，不依赖物品纹理；相邻物品与同一物品的多格体积遵循相同连接规则。绘制颜色复用主题的 `accent`、`inventory.border` 和范围条的 `progress.track` / `progress.fill`。

## 玩家腰部上限

`PlayerContainerLimits.set(ServerPlayerEntity player, int limit)` 在逻辑服务器主线程修改并原子保存玩家变量，支持 0..16，缺省为 2。已有装备数大于新上限或转移仍在运行时拒绝修改。管理员可用 `/bs3d waist-limit <玩家选择器> <数量>`，例如 `/bs3d waist-limit @s 2`；命令要求权限等级 2，普通业务消息不提供改写上限的入口。

## 附属只读数据与界面信息

2026-10-14 未发布源码增加 `InventoryAccess.snapshot(ServerPlayerEntity)`：仅服务端主线程调用，返回不可变穿戴存档及原版槽位、已授权地面容器的栈副本；不提供事务写入权限。地面键是实例 UUID，取快照时重新验证租约、维度和距离；地面物品不属于随身库存。

客户端 `ContainerDetails.register(Identifier, Provider)` 在初始化时登记短文本提供者；`summary()` 显示背包页总览，`container(String containerId)` 根据容器 UUID 提供标题附加文字。空文本不增加占位，绘制沿用当前主题；宿主统一裁切标题，附属不接管场景或输入。身份重复时拒绝注册。API 未冻结，接入方必须使用包含这些入口的新产物。

容器物品的 UUID 和内容属于保存契约。集成方应移动真实 ItemStack，不能复用同一 UUID 创建多份库存；嵌套限制和字段说明见[通信契约](Protocol.md)。

## 地面容器接入

注册的容器物品共用潜行右键放置入口；可穿戴类型使用同一份模型资产，不可穿戴类型使用注册物品的原版模型。`GroundBagBlockEntity` 只承载公开外观，禁止向它塞入另一份权威库存。地面目录、访问权、距离检查与跨文件恢复由 `GroundInventory` 管理，物品树和计时转移仍复用 `ContainerTree` 与 `TransferService`。

自定义账户不支持地面库存时保留 `Account.ground()` 的空默认值。实现地面库存时必须在 `ground()` 中仅提供当前已授权根容器副本，在 `groundRevisions()` 中提供版本，并实现三参数 `commit` 的联合提交；不能先保存个人库存、稍后再依赖区块自动保存地面内容。现有 Fabric 实现使用 `GroundJournal` 完成恢复，协议与保存版本见[通信和持久化](Protocol.md)。
