#!/usr/bin/env python3
"""把固定版本的 Bong 收纳装备转换为 BS3D 自带模型与物品清单。"""

import argparse
import copy
import hashlib
import json
from pathlib import Path
import uuid

ROOT = Path(__file__).resolve().parents[1]
SOURCE_REF = "c01576513e451490bd73df12513227bb8637222b"
MODEL_PIXELS_PER_BLOCK = 16
BODY_TOP_PIXELS = 24
MODEL_CENTER_PIXELS = 8
TORSO_Y_BLOCKS = 0.38
TORSO_Z_BLOCKS = 0.36
TORSO_SCALE = 0.8
WAIST_SCALE = 0.5
WAIST_MOUNTS = ("WAIST_RIGHT_FRONT", "WAIST_LEFT_FRONT", "WAIST_LEFT_BACK", "WAIST_RIGHT_BACK")
SOURCE_BLOBS = {
    "gu_jia_bei_lou": "fd7ce4260c70f80b73cca4649a18560407792ae7",
    "xiu_kuang_bei_jia": "ff6c3f5b168ba629e4451058a76f4b548380c506",
    "za_pi_xing_nang": "c8e30894ca079a140cc4490332c3af50121091db",
    "pi_nang_xiong_gua": "54128c7993a1a4f0c46f763baf51ba0cab556648",
    "herb_pouch": "fea6c772a1889f34776112f706b6b1d74a743fd9",
    "ore_sack": "99e7fc54df4d25833229a18e204584712d362d9b",
    "water_skin": "c5dc448371d6218d76d60ddcd864e0b90172a58f",
    "moisture_guard": "ffaaf120339ceaf15732a5d29721a72894bebf80",
    "coin_box": "906ceabec09a74f2b5c9972df9945e9ef891ae0d",
}

# 容量与外部占格均按宽、高、深排列，单位为格；不从模型外观推导权威容量。
GEAR = (
    ("gu_jia_bei_lou", "GuJiaBeiLou", "骨架背篓", "Bone Frame Basket", (3, 4, 3), (3, 4, 3), "back", 180, (),
     "兽骨框架托起的草编背篓，装备在背后。", "A woven basket on a bone frame, worn on the back."),
    ("xiu_kuang_bei_jia", "XiuKuangBeiJia", "锈框背架", "Rust Frame Pack", (4, 4, 2), (4, 4, 3), "back", 180, (),
     "带底托和绑绳的锈铁背架，装备在背后。", "A rusted carrying frame with a shelf and lashings, worn on the back."),
    ("za_pi_xing_nang", "ZaPiXingNang", "杂皮行囊", "Patchwork Hide Pack", (3, 4, 2), (3, 4, 3), "back", 180, (),
     "以多种兽皮拼缝的行囊，装备在背后。", "A travel pack sewn from assorted hides, worn on the back."),
    ("pi_nang_xiong_gua", "PiNangXiongGua", "皮囊胸挂", "Hide Chest Rig", (4, 2, 1), (4, 3, 2), "front", 0, (),
     "胸前排列皮囊与挂瓶的收纳挂具。", "A chest rig with leather pouches and hanging bottles."),
    ("herb_pouch", "HerbPouch", "灵草囊", "Herb Pouch", (1, 2, 2), (2, 2, 2), "waist", 90, ("belt_strap",),
     "束口粗布草药袋，可沿腰环调整位置。", "A drawstring herb pouch worn around the waist."),
    ("ore_sack", "OreSack", "矿石袋", "Ore Sack", (2, 2, 2), (2, 3, 2), "waist", 180, ("belt_rope",),
     "皮底加固的粗布袋，可沿腰环调整位置。", "A cloth sack with a reinforced leather base, worn at the waist."),
    ("water_skin", "WaterSkin", "水囊", "Waterskin", (1, 2, 1), (1, 3, 2), "waist", -90,
     ("strap_shoulder", "strap_front", "strap_back", "belt_waist"),
     "带骨塞的兽皮囊；当前作为普通收纳容器使用。", "A hide waterskin with a bone stopper; currently stores items, not fluid."),
    ("moisture_guard", "MoistureGuard", "防潮包", "Moss Wrapped Pouch", (2, 1, 2), (2, 2, 2), "waist", -90, ("belt_rope",),
     "覆苔捆绳的小布包；防潮外观不提供额外保鲜效果。", "A moss wrapped pouch; its appearance grants no preservation effect."),
    ("coin_box", "CoinBox", "骨币匣", "Bone Coin Box", (2, 1, 1), (2, 1, 2), "waist", 0,
     ("belt_strap", "belt_buckle", "belt_tab", "rags_front", "rags_back"),
     "带铁搭扣的腰挂木匣，适合小件物品。", "A wooden waist box with an iron clasp for small items."),
)


def groups(nodes):
    for node in nodes:
        if isinstance(node, dict):
            yield node
            yield from groups(node.get("children", []))


def adapt_model(original, item_id, mount, yaw_degrees, removed_groups):
    # 只剥离固定人体腰带本身的立方体，保留组下的包体、挂环与原骨骼层级。
    model = copy.deepcopy(original)
    if model.get("animations"):
        raise ValueError(f"{item_id} 已出现动画，需重新核对关键帧的坐标变换")
    removed = set()
    for group in groups(model["outliner"]):
        if group["name"] in removed_groups:
            removed.update(child for child in group["children"] if isinstance(child, str))
            group["children"] = [child for child in group["children"] if isinstance(child, dict)]
    if item_id == "water_skin":
        # 同一组同时放了囊口挂环与胸前肩带环；移除后者，保留囊口的 link_*。
        strap_rings = {cube["uuid"] for cube in model["elements"] if cube["name"].startswith(("ring_f_", "ring_b_"))}
        removed.update(strap_rings)
        for group in groups(model["outliner"]):
            group["children"] = [child for child in group["children"]
                                 if not isinstance(child, str) or child not in strap_rings]
    model["elements"] = [cube for cube in model["elements"] if cube["uuid"] not in removed]
    cubes = model["elements"]
    top_pixels = max(cube["to"][1] for cube in cubes)
    if mount == "waist":
        scale = WAIST_SCALE
        bottom_pixels = min(cube["from"][1] for cube in cubes)
        center_x = (min(cube["from"][0] for cube in cubes) + max(cube["to"][0] for cube in cubes)) / 2
        center_z = (min(cube["from"][2] for cube in cubes) + max(cube["to"][2] for cube in cubes)) / 2
        shift = (MODEL_CENTER_PIXELS - center_x / scale, -bottom_pixels / scale,
                 MODEL_CENTER_PIXELS - center_z / scale)
    else:
        scale = TORSO_SCALE
        # 逆解 WornModelTransform 与 ModelAsset 的居中；原人体脚底 y=0、肩顶 y=24。
        # 导入后再次穿戴时恢复源模型的真实尺寸，胸背挂带不会缩进身体。
        body_anchor_pixels = BODY_TOP_PIXELS - TORSO_Y_BLOCKS * MODEL_PIXELS_PER_BLOCK
        shift = (MODEL_CENTER_PIXELS, (top_pixels - 2 * body_anchor_pixels) / scale,
                 MODEL_CENTER_PIXELS + (1 if mount == "front" else -1)
                 * TORSO_Z_BLOCKS * MODEL_PIXELS_PER_BLOCK / scale)
    for element in cubes:
        for key in ("from", "to", "origin"):
            element[key] = [round(value / scale + shift[axis], 6) for axis, value in enumerate(element[key])]
    for group in groups(model["outliner"]):
        group["origin"] = [round(value / scale + shift[axis], 6) for axis, value in enumerate(group["origin"])]
    center_y = max(cube["to"][1] for cube in cubes) / 2
    # 在外层旋转完整模型，保持任意立方体旋转、逐面 UV 与已有分组的组合顺序。
    model["outliner"] = [{
        "name": "bs3d_mount", "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"bs3d:{item_id}/mount")),
        "origin": [MODEL_CENTER_PIXELS, center_y, MODEL_CENTER_PIXELS],
        "rotation": [0, yaw_degrees, 0], "children": model["outliner"],
    }]
    for texture in model["textures"]:
        if not texture.get("source", "").startswith("data:image/png;base64,"):
            raise ValueError(f"{item_id} 缺少内嵌 PNG")
        for key in ("path", "relative_path", "folder"):
            texture.pop(key, None)
    return model


def json_text(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def build_outputs(source):
    outputs = {}
    entries = ["# 从 Bong 导入的收纳装备；容量单位为格。修改后客户端和服务端须同时更新。", "version: 1", ""]
    provenance = {"repository": "https://github.com/Kizunad/Bong", "commit": SOURCE_REF, "models": []}
    resource = ROOT / "server/src/main/resources/bs3d/bong-gear"
    languages = {}
    for locale in ("zh_cn", "en_us"):
        path = ROOT / f"client/src/main/resources/assets/bs3d/lang/{locale}.json"
        languages[locale] = (path, json.loads(path.read_text(encoding="utf-8")))
    for item_id, name, chinese, english, inside, outside, mount, yaw, removed, zh_description, en_description in GEAR:
        source_path = f"modelScript/gear/{item_id}/{name}_tex.bbmodel"
        data = (source / source_path).read_bytes()
        source_sha = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if source_sha != SOURCE_BLOBS[item_id]:
            raise ValueError(f"{source_path} 与固定版本 {SOURCE_REF} 不一致，请重新核对坐标适配")
        model = adapt_model(json.loads(data), item_id, mount, yaw, removed)
        outputs[resource / f"models/{name}.bbmodel"] = json_text(model)
        mounts = WAIST_MOUNTS if mount == "waist" else ("TORSO_" + mount.upper(),)
        entries.extend([
            "items {", f'  id: "bs3d:{item_id}"',
            f'  description_translation_key: "item.bs3d.{item_id}.description"',
            "  container {", f'    title: "{chinese}"', f'    model: "bs3d:{item_id}"',
            f"    inside {{ width: {inside[0]} height: {inside[1]} depth: {inside[2]} }}",
            f"    outside {{ width: {outside[0]} height: {outside[1]} depth: {outside[2]} }}",
            *[f"    mounts: {value}" for value in mounts],
            f'    asset {{ file: "models/{name}.bbmodel" }}', "  }", "}", "",
        ])
        # 物品栏和手持沿用既有动态渲染入口；缩放抵消导入时的挂载比例补偿。
        item_scale = 0.4 if mount == "waist" else 0.64
        outputs[ROOT / f"client/src/main/resources/assets/bs3d/models/item/{item_id}.json"] = json_text({
            "parent": "builtin/entity", "gui_light": "front", "display": {
                "gui": {"rotation": [20, 35, 0], "scale": [item_scale] * 3},
                "thirdperson_righthand": {"translation": [0, 2, 0], "scale": [item_scale] * 3},
                "firstperson_righthand": {"scale": [item_scale] * 3},
            },
        })
        for locale, title, description in (("zh_cn", chinese, zh_description), ("en_us", english, en_description)):
            translations = languages[locale][1]
            translations[f"item.bs3d.{item_id}"] = title
            translations[f"item.bs3d.{item_id}.description"] = description
        provenance["models"].append({
            "id": f"bs3d:{item_id}", "source": source_path,
            "source_blob_sha1": source_sha,
            "removed_body_groups": list(removed), "mount": mount,
            "removed_element_prefixes": ["ring_f_", "ring_b_"] if item_id == "water_skin" else [],
            "cubes": len(model["elements"]),
        })
    outputs[resource / "items.txtpb"] = "\n".join(entries)
    outputs[resource / "sources.json"] = json_text(provenance)
    for path, translations in languages.values():
        outputs[path] = json_text(translations)
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help=f"Bong {SOURCE_REF[:9]} 检出或相关文件快照")
    parser.add_argument("--check", action="store_true", help="只核对已生成文件，不写入")
    args = parser.parse_args()
    changed = []
    for path, content in build_outputs(args.source).items():
        if path.is_file() and path.read_text(encoding="utf-8") == content:
            continue
        changed.append(str(path.relative_to(ROOT)))
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    if args.check and changed:
        parser.exit(1, "生成产物不一致：\n" + "\n".join(changed) + "\n")
    print(f"Bong 装备：{len(GEAR)} 件，{'校验通过' if args.check else f'写入 {len(changed)} 个文件'}")


if __name__ == "__main__":
    main()
