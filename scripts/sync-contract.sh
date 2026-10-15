#!/usr/bin/env bash
# 从知衍 Windows 仓库按字节同步 contract/，按 checksums.json 校验，并更新 UPSTREAM.md。
# 用法：scripts/sync-contract.sh <知衍仓库根目录>
# 等价的 PowerShell 脚本：scripts/sync-contract.ps1
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "用法：scripts/sync-contract.sh <知衍仓库根目录>" >&2
  exit 2
fi

source_root=$1
script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
source_contract="$source_root/contract"
dest_contract="$repo_root/contract"

if [[ ! -d "$source_contract" ]]; then
  echo "找不到来源契约目录：$source_contract" >&2
  exit 2
fi
if [[ ! -f "$source_contract/checksums.json" ]]; then
  echo "来源契约没有 checksums.json" >&2
  exit 2
fi

commit=$(git -C "$source_root" rev-parse HEAD)
subject=$(git -C "$source_root" log -1 --format=%s)

mkdir -p "$dest_contract"
copied=0
while IFS= read -r -d '' file; do
  relative=${file#"$source_contract"/}
  if [[ "$relative" == "UPSTREAM.md" ]]; then
    continue
  fi
  mkdir -p "$dest_contract/$(dirname "$relative")"
  cp -- "$file" "$dest_contract/$relative"
  copied=$((copied + 1))
done < <(find "$source_contract" -type f -print0)

python - "$dest_contract" << 'PY'
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1])
checksums = json.loads((root / "checksums.json").read_text(encoding="utf-8"))
failed = False
for name, expected in sorted(checksums.items()):
    path = root / name
    if not path.is_file():
        print(f"缺少文件：{name}", file=sys.stderr)
        failed = True
        continue
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        print(f"校验和不符：{name}\n  期望 {expected}\n  实际 {actual}", file=sys.stderr)
        failed = True
if failed:
    raise SystemExit(1)
print(f"校验 {len(checksums)} 个文件")
PY

python "$script_dir/record-contract-upstream.py" \
  "$dest_contract" "$source_root" "$commit" "$subject" "scripts/sync-contract.sh"

echo "同步完成：来源 $commit ，复制 $copied 个文件。"
