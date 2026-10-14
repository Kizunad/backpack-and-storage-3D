#!/usr/bin/env python3
"""检查唯一文档源 docs/wiki 的页面与内部链接。"""
import argparse
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check"])
    parser.parse_args()
    pages = set((ROOT / "docs/wiki").glob("*.md"))
    links = 0
    if not pages:
        raise ValueError("缺少 Wiki 文档")
    for page in sorted(pages):
        content = page.read_text()
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*|_Sidebar", page.stem):
            raise ValueError(f"不稳定的页面名称：{page}")
        if not content.strip() or len(re.findall(r"^```", content, re.MULTILINE)) % 2:
            raise ValueError(f"空页面或未闭合代码块：{page}")
        for link in re.findall(r"\]\(([^)]+)\)", content):
            parsed = urlsplit(link)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            destination = (page.parent / unquote(parsed.path)).resolve()
            if destination not in pages:
                raise ValueError(f"无效内部链接：{page} -> {link}")
            links += 1
    print(f"Wiki 检查通过：{len(pages)} 页，{links} 个内部链接。")


if __name__ == "__main__":
    main()
