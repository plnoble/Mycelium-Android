#!/usr/bin/env bash
# release-guard.sh - the release gate for Mycelium Android (P2-2b step 6).
#
# .github/workflows/release.yml calls this after the signed release APK has been built. It can also
# be run locally with Git for Windows bash (see docs/RELEASING.md). The checks, in order:
#
#   1. the release tag matches versionName in app/build.gradle.kts;
#   2. docs/releases/<tag>.md exists and is not empty (the Chinese release notes);
#   3. versionCode is strictly greater than the previous release tag's versionCode;
#   4. the APK's signing certificate SHA-256 fingerprint equals the pinned value in
#      docs/releases/signing-cert-sha256.txt.
#
# A failed check prints a Chinese reason to stderr and exits 1. A successful run ends with a line
# saying the tag can be released. When GITHUB_STEP_SUMMARY is set (GitHub Actions) the verified
# fingerprint is appended to the job summary.
#
# Usage:
#   bash scripts/release-guard.sh --tag v0.4.0 --apk mycelium-v0.4.0.apk
#
# Options (relative paths are resolved from the repository root):
#   --tag <tag>                  default: $GITHUB_REF_NAME, else "git describe --tags --exact-match"
#   --apk <path>                 required: the signed release APK
#   --release-notes <path>       default: docs/releases/<tag>.md
#   --expected-cert-file <path>  default: docs/releases/signing-cert-sha256.txt
#   --previous-tag <tag>         default: newest v* tag reachable from HEAD^
#   --version-code <n>           default: versionCode read from app/build.gradle.kts
#   --apksigner <path>           default: autodetected (Android SDK build-tools, then PATH)
#   -h, --help                   print this header
#
# Every option except --tag and --apk is an override of a value the gate should derive itself. They
# are rejected when CI is detected (CI or GITHUB_ACTIONS), so a pipeline cannot bypass the
# fingerprint, the release notes or the versionCode check. They exist for the local demonstration.

set -uo pipefail

GRADLE_FILE="app/build.gradle.kts"
DEFAULT_CERT_FILE="docs/releases/signing-cert-sha256.txt"

TAG=""
APK=""
NOTES=""
CERT_FILE=""
PREV_TAG=""
VERSION_CODE=""
APKSIGNER=""
OVERRIDES=""

info() { printf '[release-guard] %s\n' "$*"; }
pass() { printf '[release-guard] 通过：%s\n' "$*"; }
warn() { printf '[release-guard] 警告：%s\n' "$*" >&2; }
abort() { printf '[release-guard] 发布中止：%s\n' "$*" >&2; exit 1; }

usage() { awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"; }

require_value() {
  if [ "$#" -lt 2 ]; then abort "参数 $1 缺少值"; fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --tag) require_value "$@"; TAG="$2"; shift 2 ;;
    --apk) require_value "$@"; APK="$2"; shift 2 ;;
    --release-notes)
      require_value "$@"; NOTES="$2"; OVERRIDES="$OVERRIDES --release-notes"; shift 2 ;;
    --expected-cert-file)
      require_value "$@"; CERT_FILE="$2"; OVERRIDES="$OVERRIDES --expected-cert-file"; shift 2 ;;
    --previous-tag)
      require_value "$@"; PREV_TAG="$2"; OVERRIDES="$OVERRIDES --previous-tag"; shift 2 ;;
    --version-code)
      require_value "$@"; VERSION_CODE="$2"; OVERRIDES="$OVERRIDES --version-code"; shift 2 ;;
    --apksigner)
      require_value "$@"; APKSIGNER="$2"; OVERRIDES="$OVERRIDES --apksigner"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) abort "未知参数：$1" ;;
  esac
done

is_ci() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then return 0; fi
  case "${CI:-}" in
    ""|0|false|False|FALSE|no|No) return 1 ;;
    *) return 0 ;;
  esac
}

# The override switches are a local demonstration tool. In CI they would be a way around check 3 and
# check 4, so the gate refuses to run at all when any of them was used.
if is_ci && [ -n "$OVERRIDES" ]; then
  abort "CI 环境下不接受覆盖参数（$OVERRIDES）；发版闸门不能被绕过"
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || true
if [ -z "$REPO_ROOT" ]; then
  abort "当前目录不在 git 仓库里，读不到版本号与标签历史"
fi
cd "$REPO_ROOT" || abort "进不去仓库根目录：$REPO_ROOT"
info "仓库根目录：$REPO_ROOT"

read_version_name() {
  grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' "$1" 2>/dev/null |
    head -1 | sed -e 's/.*"\(.*\)"/\1/'
}

read_version_code() {
  grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$1" 2>/dev/null |
    head -1 | grep -oE '[0-9]+' | head -1
}

resolve_apksigner() {
  local root version candidate found
  if [ -n "$APKSIGNER" ]; then
    if [ -f "$APKSIGNER" ]; then printf '%s\n' "$APKSIGNER"; return 0; fi
    return 1
  fi
  # Android SDK build-tools, newest version first; CI/Linux has "apksigner", Git for Windows only
  # ships "apksigner.bat".
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk"; do
    [ -n "$root" ] || continue
    [ -d "$root/build-tools" ] || continue
    for version in $(ls -1 "$root/build-tools" 2>/dev/null | sort -Vr); do
      for candidate in "$root/build-tools/$version/apksigner" "$root/build-tools/$version/apksigner.bat"; do
        if [ -f "$candidate" ]; then printf '%s\n' "$candidate"; return 0; fi
      done
    done
  done
  found="$(command -v apksigner 2>/dev/null)"
  if [ -n "$found" ]; then printf '%s\n' "$found"; return 0; fi
  return 1
}

# ---------------------------------------------------------------- check 1: tag and versionName

if [ -z "$TAG" ]; then
  if [ -n "${GITHUB_REF_NAME:-}" ]; then
    TAG="$GITHUB_REF_NAME"
  else
    TAG="$(git describe --tags --exact-match 2>/dev/null)" || TAG=""
  fi
fi
if [ -z "$TAG" ]; then
  abort "取不到发布标签：用 --tag 指定，或在打了标签的提交上运行"
fi
case "$TAG" in
  v[0-9]*) ;;
  *) abort "标签格式不对：$TAG（应当形如 v0.4.0）" ;;
esac
info "发布标签：$TAG"

info "检查 1/4：标签与 versionName 一致"
VERSION_NAME="$(read_version_name "$GRADLE_FILE")"
if [ -z "$VERSION_NAME" ]; then
  abort "读不到 $GRADLE_FILE 里的 versionName"
fi
if [ "${TAG#v}" != "$VERSION_NAME" ]; then
  abort "标签($TAG) 与 versionName($VERSION_NAME) 不一致"
fi
pass "标签 $TAG 与 versionName $VERSION_NAME 一致"

# ---------------------------------------------------------------- check 2: Chinese release notes

info "检查 2/4：发布说明存在且非空"
if [ -z "$NOTES" ]; then
  NOTES="docs/releases/$TAG.md"
fi
if [ ! -e "$NOTES" ]; then
  abort "发布说明缺失：$NOTES"
fi
if [ ! -f "$NOTES" ]; then
  abort "发布说明不是普通文件：$NOTES"
fi
if [ ! -s "$NOTES" ]; then
  abort "发布说明是空文件：$NOTES"
fi
pass "发布说明存在且非空：$NOTES（$(wc -c < "$NOTES" | tr -d '[:space:]') 字节）"

# ---------------------------------------------------------------- check 3: versionCode growth

info "检查 3/4：versionCode 比上一个标签大"
if [ -z "$VERSION_CODE" ]; then
  VERSION_CODE="$(read_version_code "$GRADLE_FILE")"
fi
if [ -z "$VERSION_CODE" ]; then
  abort "读不到 $GRADLE_FILE 里的 versionCode"
fi
case "$VERSION_CODE" in
  *[!0-9]*) abort "versionCode 不是数字：$VERSION_CODE" ;;
esac

if [ -z "$PREV_TAG" ]; then
  if [ "$(git rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
    warn "当前是浅克隆，可能取不到上一个版本标签（CI 需要 checkout 的 fetch-depth: 0）"
  fi
  PREV_TAG="$(git tag --list 'v*' --merged HEAD^ --sort=-v:refname 2>/dev/null | head -1)"
fi
if [ -z "$PREV_TAG" ]; then
  warn "没有找到上一个版本标签，跳过 versionCode 增长检查（首个发布？）"
else
  PREV_CODE="$(git show "$PREV_TAG:$GRADLE_FILE" 2>/dev/null | read_version_code -)"
  if [ -z "$PREV_CODE" ]; then
    abort "读不到 $PREV_TAG 的 versionCode，无法确认版本号增长（历史不完整？）"
  fi
  if [ "$VERSION_CODE" -le "$PREV_CODE" ]; then
    abort "versionCode 没有增长：本版 $VERSION_CODE 不大于 $PREV_TAG 的 $PREV_CODE"
  fi
  pass "versionCode $VERSION_CODE 大于 $PREV_TAG 的 $PREV_CODE"
fi

# ---------------------------------------------------------------- check 4: signing fingerprint

info "检查 4/4：签名证书 SHA-256 指纹一致"
if [ -z "$APK" ]; then
  abort "缺少 --apk：签名指纹检查需要签名后的 release APK"
fi
if [ ! -f "$APK" ]; then
  abort "APK 不存在：$APK"
fi
if [ -z "$CERT_FILE" ]; then
  CERT_FILE="$DEFAULT_CERT_FILE"
fi
if [ ! -f "$CERT_FILE" ]; then
  abort "期望指纹文件不存在：$CERT_FILE"
fi
EXPECTED_FP="$(grep -oE '[0-9a-fA-F]{64}' "$CERT_FILE" | head -1 | tr 'A-F' 'a-f')"
if [ -z "$EXPECTED_FP" ]; then
  abort "期望指纹文件里没有 64 位十六进制指纹：$CERT_FILE"
fi

APKSIGNER_BIN="$(resolve_apksigner)" || APKSIGNER_BIN=""
if [ -z "$APKSIGNER_BIN" ]; then
  abort "找不到 apksigner：设置 ANDROID_HOME，或用 --apksigner 指定"
fi
info "apksigner：$APKSIGNER_BIN"

VERIFY_RAW="$("$APKSIGNER_BIN" verify --print-certs "$APK" 2>&1)"
VERIFY_STATUS=$?
if [ "$VERIFY_STATUS" -ne 0 ]; then
  HINT="$(printf '%s\n' "$VERIFY_RAW" | grep -iE 'exception|error|no such file' | head -1 | tr -d '\r')"
  abort "apksigner 校验 ${APK} 失败（退出码 $VERIFY_STATUS）：${HINT:-没有可读的错误输出}"
fi

SIGNER_COUNT="$(printf '%s\n' "$VERIFY_RAW" | grep -cE '^Signer #[0-9]+ certificate SHA-256 digest')"
if [ "$SIGNER_COUNT" -ne 1 ]; then
  abort "签名者数量不是 1（实际 $SIGNER_COUNT）：$APK"
fi
ACTUAL_FP="$(printf '%s\n' "$VERIFY_RAW" | grep -E '^Signer #[0-9]+ certificate SHA-256 digest' |
  head -1 | sed -e 's/.*: *//' | tr -d '\r' | tr 'A-F' 'a-f')"
if [ -z "$ACTUAL_FP" ]; then
  abort "apksigner 没有给出签名证书的 SHA-256 指纹：$APK"
fi

info "签名指纹（实际）：$ACTUAL_FP"
info "签名指纹（期望）：$EXPECTED_FP"
if [ "$ACTUAL_FP" != "$EXPECTED_FP" ]; then
  abort "签名指纹不一致（$CERT_FILE）"
fi
pass "签名指纹与 $CERT_FILE 一致"

# ---------------------------------------------------------------- done

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    printf '### release-guard：%s\n\n' "$TAG"
    printf -- '- 签名指纹（apksigner 实测）：`%s`\n' "$ACTUAL_FP"
    printf -- '- versionCode：%s\n' "$VERSION_CODE"
    printf -- '- 结论：四项检查全部通过\n'
  } >> "$GITHUB_STEP_SUMMARY"
fi

printf '[release-guard] 全部检查通过：%s 可以发布\n' "$TAG"
exit 0