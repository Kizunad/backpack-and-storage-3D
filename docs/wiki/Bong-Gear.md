# Bong 收纳装备

2026-10-23：新增 9 件真实容器物品，沿用 BS3D 的计时转移、嵌套、穿戴、地面放置与保存逻辑。客户端和服务端须同时安装本轮版本。业务协议仍为 v6，玩家保存格式仍为 3。

## 获取与容量

在创造模式“工具与实用物品”分类取得，或使用 `/give @s bs3d:gu_jia_bei_lou` 等原版命令。三个背负件只允许背后挂点，皮囊胸挂只允许胸前挂点；其余五件共用连续腰环和玩家腰部数量上限（默认两件）。从手中拖到身体对应位置穿戴，点击模型进入内景。

下表尺寸均为**宽 × 高 × 深，单位为格**。这些容量是 BS3D 本次设定，Bong 原模型不包含库存容量定义。

| 名称 | 物品 ID | 内部容量 | 外部占格 | 挂点 |
|---|---|---|---|---|
| 骨架背篓 | `bs3d:gu_jia_bei_lou` | 3 × 4 × 3 | 3 × 4 × 3 | 背后 |
| 锈框背架 | `bs3d:xiu_kuang_bei_jia` | 4 × 4 × 2 | 4 × 4 × 3 | 背后 |
| 杂皮行囊 | `bs3d:za_pi_xing_nang` | 3 × 4 × 2 | 3 × 4 × 3 | 背后 |
| 皮囊胸挂 | `bs3d:pi_nang_xiong_gua` | 4 × 2 × 1 | 4 × 3 × 2 | 胸前 |
| 灵草囊 | `bs3d:herb_pouch` | 1 × 2 × 2 | 2 × 2 × 2 | 腰环 |
| 矿石袋 | `bs3d:ore_sack` | 2 × 2 × 2 | 2 × 3 × 2 | 腰环 |
| 水囊 | `bs3d:water_skin` | 1 × 2 × 1 | 1 × 3 × 2 | 腰环 |
| 防潮包 | `bs3d:moisture_guard` | 2 × 1 × 2 | 2 × 2 × 2 | 腰环 |
| 骨币匣 | `bs3d:coin_box` | 2 × 1 × 1 | 2 × 1 × 2 | 腰环 |

所有类型当前均收纳普通真实 ItemStack。水囊没有液体存储能力，防潮包没有额外保鲜效果，灵草囊与骨币匣没有物品过滤规则。重量等附属属性继续由相应附属的数据包规则提供。

## 模型与加载

模型来源为 [Bong](https://github.com/Kizunad/Bong) 的 `c01576513e451490bd73df12513227bb8637222b`，对应 [PR #2419](https://github.com/Kizunad/Bong/pull/2419) 与 [PR #2420](https://github.com/Kizunad/Bong/pull/2420)。源文件位于 `modelScript/gear/<id>/*_tex.bbmodel`，包含逐面 UV 与内嵌 PNG。

`server/src/main/resources/bs3d/bong-gear/items.txtpb` 集中登记物品、容量、挂点与模型路径，common 服务端 JAR 同时用于客户端安装。模型通过已有的[物品清单](Item-Registry.md)自动加载，已有 `config/bs3d/config.toml` 不必修改。玩家登记同一模型 ID 时仍优先使用本地模型，F3+T 重载规则见[模型资源](Model-Resources.md)。

原模型按整个人体坐标建模。本次转换抵消 BS3D 的挂载缩放、换算人体原点并调整胸背朝向，保留立方体旋转、逐面 UV 和骨骼层级。三件背负装备与胸挂保留整体挂具；五件腰挂装备剥离人体固定腰带或肩带，保留包体和局部挂环，使它们能沿腰环移动。水囊另外移除肩带上的两枚远端环。

模型里的草药、矿石、卷铺盖、陶罐、骨币等是固定外观装饰，不是库存实例，不会自动生成物品；实际内景只显示服务器快照中的真实 ItemStack。九份源模型均没有开合动画，点击后直接打开内景。尚未制作新的开合动画或专属声音，使用现有默认声音。

## 更新源文件

转换脚本固定源版本并校验九份原文件的 Git blob SHA-1。它生成模型、物品清单、原版物品模型入口、中英文语言键及 `sources.json` 来源记录：

```sh
python3 "scripts/import_bong_gear.py" "/path/to/Bong"
python3 "scripts/import_bong_gear.py" "/path/to/Bong" --check
```

升级源模型时先核对脚本中的源版本、SHA、挂带剥离与坐标适配。不要把新文件直接覆盖到生成目录而跳过核验。验证范围与本次环境限制见[验证状态](Validation.md)。
