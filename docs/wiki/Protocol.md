# 通信和持久化

适用：`0.1.0-SNAPSHOT`，业务协议 **v8**、Framework wire **v2**、玩家保存格式 **4**、地面保存格式 **1**，未发布。唯一消息定义为 `shared/src/main/proto/volume.proto`；生成 Java 类不提交。

2026-11-03：非容器装备新增 `State.wearables`、`SavedInventory.worn_wearables`、`Placement.wearable_layer` 与 `WornModel.wearable_slot`。旧格式 3 的纯容器存档可读，下一次转移升级为 4。层绑定、占格保留、广播隐私与双端更新要求见[非容器穿戴](Wearables.md)。

统一通道为 `kizuna_inventory_ui:message`，业务模块为 `bs3d:inventory`。玩家身份来自 Framework 认证连接，客户端不能选择其他玩家的库存。

| 消息 | 方向 | 语义 |
|---|---|---|
| `bs3d:open` | 客户端 → 服务器 | 获取会话与状态；`include_nearby_ground=true` 同时授权附近地面模型 |
| `bs3d:move` | 客户端 → 服务器 | 提交来源、目标、朝向、库存修订号和操作 UUID，同一 tick 校验并提交 |
| `bs3d:equip` | 客户端 → 服务器 | 程序接口调整已穿戴容器的绝对位置，校验后原子保存 |
| `bs3d:cancel` | 客户端 → 服务器 | 兼容空操作；即时事务没有可取消阶段 |
| `bs3d:state` | 服务器 → 客户端 | 完整容器树、非容器装备目录、真实物品与原版槽展示栈、腰部上限、终态回执和完成声音 |
| `bs3d:appearance` | 服务器 → 客户端 | 本人与原版实体跟踪范围内玩家的装备外观，使用 `EquipmentAppearance` |
| `bs3d:ground_opened` | 服务器 → 客户端 | 原版右键校验成功并发送状态之后，通知打开指定地面内景 |
| `bs3d:close_ground` | 客户端 → 服务器 | 释放指定地面容器访问权；空列表释放当前连接全部地面容器 |

## 地面背包

放置、右键打开及空手潜行收回沿用原版方块交互包，客户端不能上传物品 NBT。放置要求手持单件容器并潜行，目标为有完整上表面支撑的可替换位置；服务端另行检查角色状态、5 方块内的交互距离、遮挡、世界边界、方块及实体碰撞和原版地形修改权限。创造模式也从手中移走原物品，不复制身份。

地面标记为 `bs3d:placed_bag`，区块仅保存容器身份、类型和朝向，客户端区块包不包含物品内容。右键成功后，服务器先把已授权的地面容器树加入 `State`，再发送 `GroundOpened`。`Container.grounded=true` 标识地面根容器，内部物品及嵌套容器继续使用原有内景和 `bs3d:move`。

打开 B／I 场景会发送 `OpenRequest.include_nearby_ground=true`，使用同一距离、遮挡与独占租约校验发现附近地面背包，模型初始合盖。登录与普通重同步的空请求不取得地面访问权。

`State.ground_revisions` 提供当前访问的地面根容器修订号；`MoveRequest.ground_revisions` 必须与它们一致，提交前重新验证。同一背包只允许一位玩家访问，其他附近玩家可以在其释放后打开。当前连接最多保留 8 个地面根容器，和个人容器合起来仍受树大小限制。退出页面、断线、死亡、换维度、超距或失去视线时释放访问；页内合盖及最小化保留访问，仍可向模型自动投放。

空手潜行右键收回时必须无人占用，整件物品连同内容回到交互手。放置、收回为即时原版交互，内景物品存取同样即时完成。地面标记不能通过普通挖掘（含创造模式）或活塞移走；不注册另一个可复制内容的方块物品。

地面目录位于世界的 `data/bs3d/ground.dat`，包含完整容器实物、维度、位置、朝向和版本。涉及地面的提交先把完整玩家 NBT 和候选地面目录写入 `pending.dat` 并刷盘，再原子替换两份最终文件。日志提交点之后如果最终文件写入失败，停止服务并在下一次启动、玩家登录之前重放；日志之前失败则保留两端原库存。区块加载回调仅记录待恢复区块，在后续服务器 tick 重建或移除标记，避免加载回调递归等待自身。备份与恢复需要保留完整世界目录及 playerdata，不能只替换其中一侧。

## 世界装备外观

外观与本人库存使用不同的消息。`EquipmentAppearance` 每次完整替换一位玩家的可见装备，以 `player_uuid` 识别玩家，`entity_id` 和 `dimension` 共同限定当前实体，避免重生、切换维度或实体 ID 复用时显示旧装备。

`models` 中的 `WornModel` 包含注册物品 ID `item_type`；容器使用挂点 `mount` 与连续腰角 `waist_angle_degrees`，非容器装备每个绑定输出一条 `wearable_slot`。不包含实例 UUID、容量、库存内容、自定义物品名称或 ItemStack NBT；嵌套和手持物品不作为穿戴模型广播。空列表表示卸下全部装备，`removed=true` 表示停止跟踪并删除缓存。

Framework 握手完成后，即使尚未打开背包 UI 也同步本人和原版实体跟踪范围内的玩家。服务端仅在外观变化或开始跟踪时发送完整快照，离开视野、维度或服务器时撤销缓存，重新进入时补发。未提交的转移不改变外观；只修改包内物品也不会触发外观广播。客户端断线清空缓存。

客户端通过玩家 RenderLayer 复用 UI 的身体挂点，继承躯干动作、环境光和受伤叠色；世界中的包盖保持关闭，UI 开盖动画只影响预览。隐身和旁观者不显示容器模型，第一人称沿用原版身体不可见行为，按 F5 可查看自身装备。

## 容器身份与位置

每件容器是真实、不可堆叠的 ItemStack。类型 ID（例如 `bs3d:herb_pouch`）与实例 UUID 不同，移动实物时保留 UUID。`Container.id` 为实例 UUID，不再使用固定的 `pouch`、`basket` 作为实例身份。

坐标为 x=宽、y=从底部向上的高度、z=深度，单位为格。`Item.cell` 是闭开长方体的最小角。`Container.size` 是内部容量，`outer_size` 是整件物品的外部占格，两者独立。

BS3D 自带类型的内部容量、外部占格与挂点见 [Bong 收纳装备](Bong-Gear.md)；附属类型按[物品清单](Item-Registry.md)或 `ContainerItems.register` 登记，挂点列表为空表示不可装备。

`Container.worn=true` 表示直接装备于身体；`player_slot` 存在表示原版库存根槽；`parent` 非空表示处于另一容器中；`grounded=true` 表示已获准访问的地面根容器。这四种位置互斥。`State.items` 包括各内景的物品，以及 `container=worn` 的身体容器实物。地面根实物通过世界交互收回，不能当作内景物品取走；其内部物品可正常拖放。原版根容器由 `Container.player_slot` 定位，其手部展示栈由 `State.player_slots` 提供。

腰部角度 `waist_angle_degrees` 为 `[0,360)` 度，0 为正前，90 为玩家左侧。`WAIST_*` 枚举保留兼容旧挂点；省略角度时按对应旧位置换算，显式角度优先。胸背使用 `TORSO_FRONT`、`TORSO_BACK`，各允许一件；容器只能装备到类型登记的挂点，腰挂类型不能装备到胸背，胸背类型不能挂腰部。

`State.waist_limit` 为服务端玩家变量，默认 2，支持 0..16；0 禁止腰挂。普通客户端消息不能修改上限，管理接口见[扩展说明](Extension-Guide.md)。

## 转移请求

`MoveRequest.target` 为目标容器实例 UUID，或以下特殊值：

- `player`：放回原版库存。`target_player_slot` 存在时精确放入当前主手、副手或盔甲；省略时只在主背包自动找空位或合并。
- `worn`：将真实容器或穿戴物装备到身体，必须提供 `worn_target`。容器使用 mount，穿戴物使用 wearable_layer，二者不能混传。位置由服务端按物品类型、层规则与玩家上限校验。

从已存物品或身体容器移动时，`item` 引用实例 UUID。从原版来源移动时，`item` 为空，发送 `player_slot` 与开始拖动时真实栈的 SHA-256 指纹。客户端不上传 NBT、容量或尺寸创建库存。

原版槽号：主背包 0..35、靴子 36、护腿 37、胸甲 38、头部 39、副手 40。双手或盔甲直接互转时，来源和目标都必须属于当前双手/盔甲，必须显式指定不同的目标槽。UI 从角色骨架命中这些槽位，支持左右惯用手。

目标手仅接受空槽或能够完整合并的同种 NBT 栈，不交换、不部分合并、不溢出到其他槽。目标盔甲槽必须为空，数量为 1，且符合原版装备部位规则，包括鞘翅、南瓜与头颅。生存模式拒绝卸下绑定诅咒装备，创造模式遵循原版例外。

`State.player_slots` 为双手与四个盔甲槽的 `PlayerSlotStack`，沿用原 `hands` 字段号 6。空槽的 `stack_nbt` 为空。非空栈提供 `base_size` 供拖动预览；`unavailable_reason` 表示不可拖动，非法 NBT 不发送给客户端。原版换装、快捷栏变化、容器内容变化或创造状态变化也会主动同步，不增加业务修订号。

## 朝向与即时事务

`MoveRequest.orientation` 与 `Item.orientation` 为 0..23 的正交朝向索引，0 为单位阵。`Item.base_size` 为未旋转尺寸，`Item.size` 为当前占格。服务端置换原始尺寸后验证边界和碰撞，同格旋转同样即时提交。收纳包使用注册类型的外部占格，不能靠旋转缩小体积。

索引与 `OrthogonalRotation` 一致：从单位阵出发，按先 Y 后 X 的顺序广度优先遍历去重。Y 正向 `(x,y,z) → (z,y,-x)`，X 正向 `(x,y,z) → (x,-z,y)`；新旋转左乘原朝向。反向为三次正向，24 种朝向无镜像，此索引不能重排。

收到 MOVE 后在同一服务器调用内校验会话、修订号、来源指纹、目标及完整容量，构造候选并原子提交。相同操作 UUID 与相同载荷重试返回既有成功结果；同 UUID 不同载荷被拒绝。

只推送 IDLE、COMPLETED 或 REJECTED，不推 RUNNING 或进度。Transfer 的旧字段号 3/4 和名称 elapsed_ticks/duration_ticks 已保留，旧计时非零或线型错误时解码拒绝；旧 RUNNING 枚举号 1 保留并拒绝。角色无行走锁，ESC 只关闭页面或合盖。回执前仅占位，不预测改动库存。

target=carried 表示按腰挂、胸背顺序寻找有空位的随身根容器，不使用地面容器或 UI 切片。模型落点使用指定容器 UUID 与 auto_place=true，保留 orientation；原版自动取回使用 target=player。Vanilla 附属的快捷取回复用原版 insertItem。

`bs3d:equip` 继续按当前修订号原子调整已穿戴容器的绝对挂点。幂等收据和地面原子日志不变；成功提交时使用当前玩家保存格式 4。

## 保存与嵌套

真实容器数据存于物品 `minecraft:custom_data` 组件的 `bs3d_container` 子标签，包含 `id`（UUID）和 `contents`（`ContainerContents` 的 protobuf 字节数组）。子容器内容递归存在对应物品的同一组件中，因此取回手中、换装和再次收纳均携带完整内容。

禁止容器放进自身或子孙，同一玩家树中不得出现重复容器身份。根深度为 0，最大嵌套深度 4，整棵树最多 32 个容器；每层均校验几何。例如锈框背架（内部 4×4×2）放得下灵草囊（外部 2×2×2），但骨架背篓（外部 3×4×3）在全部 24 朝向均放不下。

`SavedInventory.worn` 保存身体容器物品与位置，`worn_wearables` 保存非容器装备的真实栈、UUID、绑定与原始收纳尺寸；`waist_limit` 为可选玩家变量，缺省按 2 解释。原版槽位中的容器与穿戴物随原版 `Inventory` 保存。业务字段 `backpack_storage_3d` 与原版槽位在同一 playerdata 文件中，提交先写完整临时文件、刷盘再原子替换；不支持原子替换则拒绝事务并恢复内存。近期 128 个成功操作收据同时保存，运行中的操作不落盘。当前版本身体容器与非容器装备死亡保留；原版槽位遵循原版掉落规则。

单件 NBT 编码上限 16 KiB，业务存档上限 192 KiB；内容嵌套同样受栈大小限制。损坏存档拒绝读取，不静默清空。运行中获得损坏或重复身份的容器时拒绝业务视图和操作，保留原物品供管理员处理。

## 升级与 API 变化

业务消息 v8 增加非容器穿戴，两端需要同步升级，不能与 v7 混用。v7 删除计时并增加的随身自动目标、地面消息与修订号，以及 v5 引入的 `bs3d:appearance` 继续保留。Framework 信封仍为 wire v2，protobuf 包名 `kizuna.backpack3d.v1` 保留，字段号不复用；没有地面目录的旧世界从空目录开始。

玩家保存格式写出 4，接受格式 3 的旧容器存档，下一次转移升级；格式 3 不得携带 `worn_wearables`。格式 1、2（两个固定容器）自首个实现提交起没有任何版本写出过，2026-11-01 随旧容器删除其升级路径，读到时按不支持的版本拒绝。新玩家不自动获赠容器。内景物品缺少 `base_size` 时以当前 `size` 作为未旋转占格，已穿戴非容器装备必须保存有效 `base_size`。新格式不可交给旧版写入。

2026-11-01 删除 `bs3d:grass_pouch`（草包）、`bs3d:back_basket`（背篓）、`bs3d:storage_box`（收纳箱）三件物品，旧存档里的实例按损坏数据处理，与其他未注册物品一样拒绝读取。

客户端 `playerSlot()`、`movePlayerStack()` 和 `wear()` 分别提供原版槽快照、部位间转移与容器/穿戴物装备入口；`container(id)` 只查询容器实例，非容器装备位于 `state().getWearablesList()`。公开 `Account.inventory()` 包含全部原版槽位，`selectedSlot()` 与 `creative()` 用于当前主手和绑定诅咒校验。`commit()` 必须将业务数据与完整原版候选原子保存。API 尚未冻结，集成方应以当前源码签名为准。

`Account.ground()` 和 `groundRevisions()` 缺省为空，原有个人库存适配器可保留原实现；支持地面容器时必须实现三参数 `commit(data, inventory, ground)` 的联合持久化。`ContainerTree` 新增接收地面根容器 Map 的构造方法，它们不属于原版额外槽位，也不进入 `SavedInventory.worn`。

参阅[接入说明](Getting-Started.md)、[操作说明](Interaction.md)和[验证记录](Validation.md)。
