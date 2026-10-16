"""Rewrite contract/UPSTREAM.md after a byte-for-byte sync. Called by the sync scripts."""
import sys
from datetime import date
from pathlib import Path

root, source, commit, subject, tool = sys.argv[1:6]
version = (Path(root) / "VERSION").read_text(encoding="utf-8").strip()
text = f"""# 契约同步来源

- 来源仓库：知衍 Windows 版（`{source}`）
- 来源提交：`{commit}`
- 来源说明：{subject}
- 契约版本：`{version}`（见 `VERSION`）
- 复制时间：{date.today().isoformat()}
- 复制方式：`{tool}` 按字节复制 `contract/` 下除本文件以外的全部内容，再按 `checksums.json` 逐文件校验 SHA256。

本目录除本文件外都来自知衍仓库，安卓侧不手工修改。契约有问题报给统筹，由 Windows 侧改并升版本号。
"""
(Path(root) / "UPSTREAM.md").write_text(text, encoding="utf-8", newline="\n")
print(f"upstream {commit} contract {version}")
