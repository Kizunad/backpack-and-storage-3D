# Protobuf 物品清单

状态：2026-10-16 源码已实现并验证，尚无正式发布版本，API 与清单格式未冻结。适用：`0.1.0-SNAPSHOT`，清单格式 **version = 1**。

附属模组（例如后续的植物学附属需要登记种子、作物与种子袋）可以用 protobuf 清单在**启动阶段**注册物品，规则与 `config/bs3d/config.toml` 一致：

- 必须写 `version: 1`；不接受未知字段、重复 ID、错误字段类型或版本。
- **整份清单先校验后注册**：任一条目非法时，同一清单中的其他条目也不注册，错误定位到 `items[i]` 和字段。
- ID 必须是完整的 `namespace:path`，不接受旧短名称；不能使用 `minecraft` 命名空间或已存在的物品 ID。
- 清单最大 256 KiB。

与 TOML 不同，物品注册决定注册表内容，**客户端和服务端必须安装相同清单**；清单错误会中止启动，而不是只输出 WARN 后跳过。Schema 位于 `shared/src/main/proto/item_registry.proto`（`kizuna.backpack3d.registry.v1`），并随 `protocolZip` 发布。

## 方式一：在 fabric.mod.json 中声明（推荐）

无需编写 Java 代码。BS3D 的 `main` 入口在注册内置容器之后，按模组 ID 排序扫描所有模组的 `custom` 字段并加载清单：

```json
{
  "id": "end_botany",
  "depends": { "backpack_storage_3d": "0.1.0-SNAPSHOT" },
  "custom": {
    "bs3d:item_registries": ["bs3d/items.txtpb"]
  }
}
```

值可以是单个字符串或字符串数组，路径相对于该模组 JAR 根目录，以 `.txtpb`（文本格式）或 `.binpb`（二进制格式）结尾，禁止绝对路径、反斜杠和 `..`。文件缺失或格式错误时启动失败并报告模组 ID 和路径。

`src/main/resources/bs3d/items.txtpb` 示例（附属 JAR 内目录结构见下方[自带模型](#自带模型动画与音效)）：

```text
version: 1

# 普通物品：占格和材质音效继续由数据包 item tag 规则决定。
items {
  id: "end_botany:chorus_seed"
  description_translation_key: "item.end_botany.chorus_seed.description"
  creative_tab: "minecraft:natural"
  optional_fields { id: "bs3d_weights:weight" json: '{"grams": 3}' }
  plain { max_count: 64 }
}

# 容器物品：字段与 ContainerItems.register 一一对应。
items {
  id: "end_botany:seed_pouch"
  description_translation_key: "item.end_botany.seed_pouch.description"
  container {
    title: "种子袋"
    model: "end_botany:seed_pouch"
    inside { width: 1 height: 1 depth: 2 }
    outside { width: 1 height: 2 depth: 2 }
    mounts: WAIST_RIGHT_FRONT
    mounts: WAIST_LEFT_FRONT
    mounts: WAIST_LEFT_BACK
    mounts: WAIST_RIGHT_BACK
    # 可选：随 JAR 分发模型，玩家无需修改 config.toml。
    asset {
      file: "models/seed_pouch.bbmodel"
      sounds { open: "sounds/pouch_open.ogg" place: "sounds/pouch_place.ogg" }
    }
  }
}
```

## 方式二：Java 入口

需要在运行时生成清单时，在附属自己的 `main` 入口（`ModInitializer.onInitialize`）中调用，必须早于物品注册表冻结：

```java
ItemRegistrations.register("end_botany", ItemRegistry.newBuilder()
        .setVersion(ItemRegistrations.FORMAT_VERSION)
        .addItems(ItemEntry.newBuilder()
                .setId("end_botany:chorus_seed")
                .setPlain(PlainItem.newBuilder().setMaxCount(64)))
        .build());
```

| 入口 | 说明 |
|---|---|
| `register(String source, ItemRegistry registry)` | 校验并注册消息，返回 `Map<Identifier, Item>`；`source` 仅用于错误定位 |
| `registerBinary(String source, byte[] bytes)` | 二进制清单，携带未知字段时拒绝 |
| `registerText(String source, String text)` | 文本格式清单 |
| `registerResource(ModContainer mod, String path)` | 读取模组内 `.txtpb` / `.binpb`，方式一即调用此入口 |

同一 ID 只能注册一次：不要既在 `fabric.mod.json` 声明又在 Java 中注册同一清单。`register`、`registerText`、`registerBinary` 没有清单目录，声明 `container.asset` 会被拒绝；需要自带模型时使用清单文件（方式一或 `registerResource`）。

## 字段

| 字段 | 约束与默认行为 |
|---|---|
| `version` | 必填，目前只能为 `1` |
| `items[].id` | 必填 `namespace:path`，清单内及注册表中唯一 |
| `description_translation_key` | 简介语言键；空字符串表示无补充简介，文案由附属语言资源提供 |
| `optional_fields` | 附属扩展字段，`id` 为完整资源 ID，`json` 必须是 JSON 对象文本；同一条目不能重复。含义同 Java 入口的 `optional` 参数，见[物品扩展](Extension-Guide.md) |
| `creative_tab` | 可选创造模式物品栏 ID；普通物品省略时不加入物品栏，容器始终在工具栏，指定时额外加入。未存在的物品栏不会报错，只是不显示 |
| `plain.max_count` | 最大堆叠 `1..64`，`0` 或省略为 64 |
| `container.title` | 必填非空显示标题 |
| `container.model` | 必填完整模型 ID，与 `config.toml` 的 `[[models]] id` 相同 |
| `container.inside` / `outside` | 必填，内部容量与外部占格，单位为格，宽、高、深均为正整数 |
| `container.mounts` | 可装备部位，不能含 `MOUNT_DEFAULT` 或重复值；空列表表示仅可收纳 |
| `container.asset` | 可选自带模型，见下节；模型 ID 取 `container.model` |
| `asset.file` | 必填 `.bbmodel` 相对路径 |
| `asset.open_animation` / `close_animation` | 省略时自动寻找 `open` / `close`；指定其他名称时必须存在；空字符串禁用该方向片段 |
| `asset.sounds` | 可选 `open`、`close`、`place`、`pickup`、`touch`，值为相对 `.ogg` 路径 |

每个条目必须恰好选择 `plain` 或 `container`。

## 自带模型、动画与音效

容器条目的 `asset` 与 `config.toml` 的 `[[models]]` 字段一一对应，只是文件来自附属 JAR：

```text
end_botany.jar
  fabric.mod.json                       # custom.bs3d:item_registries = ["bs3d/items.txtpb"]
  bs3d/
    items.txtpb
    models/seed_pouch.bbmodel
    sounds/pouch_open.ogg
    sounds/pouch_place.ogg
  assets/end_botany/models/item/seed_pouch.json   # parent 为 builtin/entity
  assets/end_botany/lang/zh_cn.json
```

- 路径相对于**清单文件所在目录**（上例为 `bs3d/`），统一使用 `/`，禁止绝对路径和 `..` 越出该目录；外部 PNG 的 `relative_path` 同样不能越出。推荐把 PNG 嵌入模型。
- 启动时两端都检查路径和文件存在，缺失即中止启动；`.bbmodel` 几何、贴图、动画和 OGG 内容由客户端加载时校验，单个模型失败只跳过该模型并输出 WARN，单个声音失败沿用默认声，规则与 TOML 相同。Blockbench 支持范围、大小限制与动画约定见[模型、动画与音效导入](Model-Resources.md)。
- 玩家 `config.toml` 中登记了同一模型 ID 时，**玩家配置优先**，附属自带资源跳过并输出 INFO；删除该登记并按 F3+T 后恢复附属模型。清空或写坏 `config.toml` 只影响玩家登记，不影响附属自带模型。
- 同一模型 ID 只能由一个条目提供 `asset`；共用外观的其他容器把 `model` 写成相同 ID 并省略 `asset`。
- 专用服务器只做启动时文件检查，不加载几何或音频。

普通物品与不可穿戴容器使用附属自己提供的原版物品模型和语言资源。

## 验证

`ItemRegistrationsTest` 覆盖文本与二进制清单、普通物品与容器、默认堆叠、描述和附属字段、整份原子校验、版本/重复/已存在/短 ID/缺少类型、文本与二进制未知字段、非法 JSON 与挂点；自带模型覆盖 ZIP 归档内按清单目录解析、动画省略与禁用、音效路径、缺失文件/越界/绝对路径/错误后缀、无目录入口拒绝及重复资产拒绝。`BundledModelTest` 从 ZIP 归档读取默认草包，确认几何、贴图和开合动画，并检查归档路径不能越界。

真实 Fabric 环境：专用服务器启动日志确认 `bs3d_smoke` 清单被自动发现并注册；Xvfb 客户端资源验收（`:client:runResourceSmoke`）确认附属自带草包模型被加载，且在清空和写坏 `config.toml` 时保留，结果见[验证状态](Validation.md)。开发环境中模组资源是目录而非 JAR，JAR 归档路径由单元测试覆盖。业务协议 v6 与保存格式不变。
