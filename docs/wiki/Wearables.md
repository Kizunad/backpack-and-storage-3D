# 非容器穿戴与分段模型

2026-11-03，未发布源码：业务协议 v8、玩家保存格式 4。本文描述 BS3D 的穿戴契约；具体穿搭内容、PA 跟骨渲染与布料动画由附属提供。

## 类型与挂点

物品清单的 `kind` 新增 `wearable`，与 `plain`、`container` 互斥。穿戴物是真实、不可堆叠的 ItemStack，有独立 UUID，没有内部容量，不进入容器目录，也不能被当作收纳目标。放进容器时仍占用 handling 规则给出的格子；穿戴后保存该尺寸，脱下不会缩成单位格。

```text
version: 1
items {
  id: "example:cloak"
  wearable {
    title: "示例披风"
    model: "example:cloak"
    slots { layer: "cape" bone: "head" segment: "hood" }
    slots { layer: "cape" bone: "chest" segment: "back" offset { z: 0.125 } }
    asset { file: "models/cloak.bbmodel" mode: MODEL_RENDER_MODE_RAW_SEGMENTS }
  }
}
```

此示例需要附属提供对应资源。`slots` 必须含 1..32 个绑定；`layer` 是小写稳定标识，支持附属使用 `namespace:path`；`bone` 使用 PA 的小写骨名，例如 `head`、`chest`、`l_forearm`、`r_shin`、`l_foot`。BS3D 只验证骨名语法，不依赖 PA Java 类，实际骨名解析由渲染附属负责。`offset` 的 x/y/z 单位为方块，必须为有限数。`segment` 是 bbmodel 顶层组名，省略表示整件；同层可绑定多个不同段，不得重复声明相同的层与段。

推荐的外观层为 `head`、`eyes`、`face`、`neck`、`left_shoulder`、`right_shoulder`、`left_wrist`、`right_wrist`、`left_leg`、`right_leg`、`left_foot`、`right_foot`、`cape`。甲层为 `armor_head`、`armor_body`、`armor_legs`、`armor_feet`，不占原版盔甲槽。

同层最多一件，多个段属于同件时只占一次。`left_shoulder` 与另一件物品的 `armor_body` 互斥。头饰、眼饰、面饰可叠穿；披风层与背包胸背挂点独立。披风覆盖背包由穿搭附属负责。甲层与原版同身体区域的装备现已双向互斥：`ArmorLayers` 映射四个人体部位，候选树同时校验原版槽和甲层；多部位装备整件接受或拒绝。防御属性仍由穿搭附属决定，BS3D 不依赖 Gear、Weights 或 PA。

Java 注册入口为 `WearableItems.register(Definition, descriptionTranslationKey, optional)`；`Definition` 包含物品 ID、标题、模型 ID 和 slots，并复制绑定列表。推荐仍使用两端共享 protobuf 清单，以便先完整校验再注册、统一提供创造栏与资源。

## 穿脱、保存与同步

`MoveRequest.target=worn` 时提供 `worn_target.wearable_layer`。指定层必须属于该类型；服务器同时占用全部层，不接受客户端提交骨名、偏移或 NBT。穿戴物请求不能同时带容器 mount 或腰角；容器请求不能带穿戴层。

事务复用 `ContainerTree`、`TransferService`、`CursorTransfer`：手持、已穿戴物与容器内物品可以互相转移，嵌套收纳后仍能恢复穿戴类型。来源指纹、修订号、重复身份、容量和层冲突在提交前检查；失败保留旧库存与收据。原版右键、菜单放入／快捷转移、发射器、装备命令和创造模式装备包也遵守甲层占用；直接调用 void 写入入口时，冲突抛出 `IllegalArgumentException`，调用方必须保留其来源物品。

刚由创造模式或 `/give` 进入服务器的新装备可能尚未经历库存 tick。穿脱事务使用原栈核对来源指纹，
仅在候选副本内为全新容器或穿搭分配身份；失败不修改原栈。已有身份子标签即使类型错误也不重建，
读取时直接拒绝损坏数据。

本人快照新增 `State.wearables`，只描述已装备非容器物品的实例 ID、标题、模型和绑定；真实展示栈仍在 `State.items` 中，位置为 `worn`。它们不出现在 `State.containers`，也不提供可打开的内景窗口。`SavedInventory.worn_wearables` 保存真实 NBT、实例 UUID、全部绑定和原始收纳尺寸，随同一 playerdata 原子保存，并沿用身体容器的死亡保留规则。

保存格式 3 的旧容器库存可读取，下一次转移升级为 4；格式 3 不允许携带非容器装备。恢复时同时校验原版甲与甲层，非法叠穿旧存档明确拒绝，不自动卸装或吞物。新格式不能交给旧版程序写回。业务协议 v8 要求两端同步更新；没有修改既有 protobuf 字段编号。

`WornEquipmentSync` 为每个绑定输出一个 `WornModel.wearable_slot`，只广播注册物品类型和绑定，不发送实例 UUID、自定义名称或完整栈。卸装、跟踪离开和重连沿用完整外观替换语义。客户端 `VolumeModule.appearances()` 可供附属取得权威外观；G0 的容器世界渲染器不绘制非容器装备，实际模型跟骨在 G1 接入。

## UI 操作

人物模型共用 `WornPreview.equipmentAt`：拖入穿戴物时选择其声明层，拖入容器时选择允许挂点；套装、双腕和双腿均按整件处理。身体任意部位和身上容器都可接收装备，腰部精确落点仍保留连续腰角。普通物品拖到容器模型继续自动收纳。地面容器不是人物装备入口。

独立背包页／工作台双击手持容器打开内景，右键内景中的容器仍打开子容器；人物上的右键单击统一留给卸装列表。

右键单击人物打开“脱掉 XXX”列表，列出全部已挂载 BS3D 容器与非容器穿搭，包括腰带、胸挂、背包、内层饰物和套装；同一实例只列一次。右拖仍旋转人物。列表滚轮翻页，点击外部、右键或 ESC 关闭；卸装成功后从权威快照刷新，拒绝原因保留在列表底部。卸装只请求转移到合法原版主背包槽位，空间不足时原装备、内容及收据均不改变。

独立 `VolumeScreen` 与工作台 `VolumeComponent` 复用同一输入入口；宿主须在自己的遮挡、窗口及菜单校验后调用共享模型方法。`ModelEquipment.worn(State)` 提供不含地面、原版手持或嵌套容器的挂载根目录。`WornPreview` 的菜单绘制、点击、滚动与关闭方法供其他宿主复用；旧常驻穿戴层标签已移除。

原版菜单宿主通过 `new WornPreview(volume, equipmentActions)` 提供自己的在途状态、反馈与卸装请求。
`EquipmentActions.unequip(item, revision)` 只提交实例身份与当前修订；宿主不得直接改库存。
Vanilla Addon 使用自己的菜单事务把挂载装备取回原版库存，避免绕过菜单身份与光标检查。
光标穿戴通过 `CursorTransfer.Request.wear(revision, placement)` 生成候选，类型、全部占层、
挂点和原版甲互斥均由 `ContainerTree` 读取真实栈验证；失败不修改输入栈和原库存。

## 原坐标分段资产

TOML 的 `mode = "raw_segments"` 对应 protobuf 的 `MODEL_RENDER_MODE_RAW_SEGMENTS`；省略或 `mode = "centered"` 沿用容器居中方式。

原坐标模式保留模型像素坐标，不自行居中、不自行缩放；调用方负责像素到方块转换、挂骨和偏移。所有可见立方体须位于具名顶层组，顶层组名不得重复。`ModelAsset.segments()` 返回可用顶层组名，`segment(name)` 返回缓存的段视图，可复用原有绘制、环境光、叠色、包围盒和命中入口。段视图保留完整父子骨链、原始逐面 UV、纹理和 UUID 动画引用。未知段明确报错，资源重载后应重新从 `ModelResources.find` 获取资产。

仍只支持 Blockbench 4.x 逐面 UV 的 cube；不新增 box UV、网格、蒙皮或布料求解器。


## 服务端装备通知

`WearableEquipment.CHANGED` 在 BS3D 个人穿戴数据替换时通知附属。监听器只能根据传入玩家的当前
真实穿戴状态重算派生效果，不得转移物品或抛错。原子保存的临时候选和失败恢复也可能触发通知，
因此监听器须幂等，不能按事件次数累加；成功保存后才同步客户端。
Gear 用固定 ID 的非持久化属性修饰重算铁甲防御，登录和复活再刷新一次。


## 互斥验证（2026-11-03）

BS3D 服务端 JUnit 111 项通过，新增三项覆盖正反向候选拒绝、不同区域仍可共穿、
非法恢复及原库存／收据不变。Gear 的真实 GameTest 另覆盖右键、甲槽放入、快捷转移、
快捷栏交换、发射器判定、装备命令槽访问、创造模式包和直接写入；
穿脱、重放、存档重连和复活属性由 Gear 负责验收。本轮未改渲染路径或生成模型。
