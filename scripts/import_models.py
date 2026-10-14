#!/usr/bin/env python3
"""复制单个 bbmodel 到客户端资源目录，输出需要手动登记的 TOML，不自动启用模型。"""
import argparse
import base64
import json
from pathlib import Path
import re

MAX_MODEL_BYTES = 16 * 1024 * 1024
MAX_TEXTURE_BYTES = 8 * 1024 * 1024


def portable_model(source):
    # 嵌入同目录内的相对贴图，导入后不再依赖编辑者电脑上的绝对路径。
    if source.suffix != ".bbmodel" or source.stat().st_size > MAX_MODEL_BYTES:
        raise ValueError("输入必须是大小不超过 16 MiB 的 .bbmodel")
    model = json.loads(source.read_text(encoding="utf-8"))
    for texture in model.get("textures", []):
        if texture.get("source", "").startswith("data:image/png;base64,"):
            continue
        relative = texture.get("relative_path", "")
        image = (source.parent / relative).resolve()
        if not relative or not image.is_relative_to(source.parent.resolve()):
            raise ValueError("外部贴图必须在模型目录内；也可以在 Blockbench 中先嵌入 PNG")
        if image.suffix != ".png" or image.stat().st_size > MAX_TEXTURE_BYTES:
            raise ValueError(f"贴图不是 PNG 或超过 8 MiB：{image}")
        texture["source"] = "data:image/png;base64," + base64.b64encode(image.read_bytes()).decode("ascii")
    return json.dumps(model, ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="单个 .bbmodel 文件")
    parser.add_argument("--id", required=True, help="注册模型 ID，例如 example:travel_bag")
    parser.add_argument("--into", type=Path, required=True, help="目标客户端的 config/bs3d 目录")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_/.-]+", args.id):
        parser.error("--id 必须使用小写命名空间及合法模型名")
    namespace, name = args.id.split(":", 1)
    if name.startswith("/") or any(part in {".", "..", ""} for part in name.split("/")):
        parser.error("模型 ID 不能包含空路径段或相对路径")
    relative = Path("models") / namespace / (name + ".bbmodel")
    target = args.into / relative
    try:
        output = portable_model(args.source)
        if len(output.encode("utf-8")) > MAX_MODEL_BYTES:
            raise ValueError("嵌入贴图后的模型超过 16 MiB")
        target.parent.mkdir(parents=True, exist_ok=True)
        # 独占创建防止覆盖用户已有模型，注册表仍由用户明确修改。
        with target.open("x", encoding="utf-8") as stream:
            stream.write(output)
    except (OSError, ValueError) as invalid:
        parser.error(str(invalid))
    print(f"已复制：{target}\n尚未启用。请在 config.toml 的 version = 1 后追加：\n")
    print(f'[[models]]\nid = "{args.id}"\nfile = "{relative.as_posix()}"\n')
    print("可选 [models.sounds]：open、close、place、pickup、touch，值为相对 OGG 路径。")
    print("保存 config.toml 后按 F3+T 加载；几何和动画兼容性由客户端日志报告。")


if __name__ == "__main__":
    main()
