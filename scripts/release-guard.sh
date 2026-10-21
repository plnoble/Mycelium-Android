#!/usr/bin/env bash
# release-guard.sh - the release gate for Mycelium Android (P2-2b step 6).
#
# .github/workflows/release.yml calls this after the signed release APK has been built. It can also
# be run locally with Git for Windows bash (see docs/RELEASING.md). The checks, in order:
#
#   1. the release tag matches versionName in app/build.gradle.kts;
#   2. docs/releases/<tag>.md exists and is not empty (the Chinese release notes);
#   3. versionCode is strictly greater than the highest versionCode already published by any formal
#      release tag in this repository (the current tag excluded). There is no "first release"
#      exception: when no previous tag can be resolved, the gate aborts instead of skipping;
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
#   --previous-tag <tag>         default: the v* tag with the highest versionCode in this repo
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
PREV_CODE=""
PREV_TAG_FOUND=""
UNREADABLE_TAGS=""
CANDIDATE_TAGS=""

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

# 注释行不参与；同一份文件里同一项出现多处赋值就是有歧义，读数的人只能拿到空值，
# 由调用方按「读不到」中止（失败关闭，建议修改第 1 条）。
strip_comment_lines() {
  grep -vE '^[[:space:]]*(//|\*|/\*)' "$1" 2>/dev/null
}

read_version_name() {
  strip_comment_lines "$1" |
    grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' |
    head -1 | sed -e 's/.*"\(.*\)"/\1/'
}

read_version_code() {
  strip_comment_lines "$1" |
    grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' |
    head -1 | grep -oE '[0-9]+' | head -1
}

# 工作树里的当前版本：versionName 与 versionCode 必须**各恰好一处**赋值，否则中止。
assert_single_version_assignment() {
  local file="$1" nameCount codeCount
  [ -f "$file" ] || abort "找不到 $file"
  nameCount="$(strip_comment_lines "$file" | grep -cE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"')"
  codeCount="$(strip_comment_lines "$file" | grep -cE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+')"
  [ "$nameCount" -eq 1 ] || abort "$file 里 versionName 必须恰好一处赋值，实际 $nameCount 处：有歧义就不放行"
  [ "$codeCount" -eq 1 ] || abort "$file 里 versionCode 必须恰好一处赋值，实际 $codeCount 处：有歧义就不放行"
}

PINNED_BUILD_TOOLS="36.0.0"

resolve_apksigner() {
  local root version candidate found
  if [ -n "$APKSIGNER" ]; then
    if [ -f "$APKSIGNER" ]; then printf '%s\n' "$APKSIGNER"; return 0; fi
    return 1
  fi
  # Android SDK build-tools: the pinned version first (release.yml installs exactly this one and its
  # --print-certs format is verified), then newest first. CI/Linux has "apksigner", Git for Windows
  # only ships "apksigner.bat". Pinned because the runner image also carries build-tools 37, whose
  # output this script could not read (2026-10-21 runs 39599697019, 39601747956); passing
  # --apksigner from the workflow is refused in CI on purpose, so the pin lives here.
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk"; do
    [ -n "$root" ] || continue
    [ -d "$root/build-tools" ] || continue
    for version in "$PINNED_BUILD_TOOLS" $(ls -1 "$root/build-tools" 2>/dev/null | sort -Vr); do
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
assert_single_version_assignment "$GRADLE_FILE"
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

info "检查 3/4：versionCode 比已发布的最高版本大"
if [ -z "$VERSION_CODE" ]; then
  VERSION_CODE="$(read_version_code "$GRADLE_FILE")"
fi
if [ -z "$VERSION_CODE" ]; then
  abort "读不到 $GRADLE_FILE 里的 versionCode"
fi
case "$VERSION_CODE" in
  *[!0-9]*) abort "versionCode 不是数字：$VERSION_CODE" ;;
esac

# The published upper bound must not depend on "reachable from HEAD^": on a shallow clone, when the
# previous tag exists only on another branch, or after a tag was deleted, that ancestry set is empty
# while releases did happen -- and a regressed versionCode would slip through. So compare every
# formal release tag in this repository (the tag being released excluded) and take the highest code.
version_code_of_tag() {
  local code
  code="$(git show "$1:$GRADLE_FILE" 2>/dev/null | read_version_code -)"
  case "$code" in
    ""|*[!0-9]*) return 1 ;;
  esac
  printf '%s\n' "$code"
}

# Fold one formal release tag into the "highest published versionCode" comparison. When its code
# cannot be read, the known baseline tag falls back to the pinned code below while every other tag
# is collected into the unreadable list and reported (the gate aborts afterwards).
consider_tag() {
  local tag="$1" code
  if ! code="$(version_code_of_tag "$tag")"; then
    # 读不出基线标签也是失败关闭（M8）：没有「退回写死版本号」这条路，
    # 否则删掉标签或改坏历史就能把版本号回退放过去。
    UNREADABLE_TAGS="$UNREADABLE_TAGS $tag"
    return 0
  fi
  if [ -z "$PREV_CODE" ] || [ "$code" -gt "$PREV_CODE" ]; then
    PREV_CODE="$code"
    PREV_TAG_FOUND="$tag"
  fi
}

# 浅克隆一律先拒绝（M8）：历史不完整时「没找到更高的标签」并不等于「没有更高的版本」，
# 拿它当通过就是失败开放。必须在计算上界**之前**拒绝，而不是等发现上界为空才警告。
if [ "$(git rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
  abort "当前是浅克隆（历史不完整），无法确认已发布的 versionCode 上界，因此不能放行；CI 要 actions/checkout 的 fetch-depth: 0 并 git fetch --tags --force，本地先 git fetch --unshallow"
fi

if [ -n "$PREV_TAG" ]; then
  # --previous-tag is an explicit local-demo override (CI rejects the override switches), so the
  # comparison follows it directly.
  if [ "$PREV_TAG" = "$TAG" ]; then
    abort "--previous-tag 与本次发布标签相同（$TAG）：版本增长要跟别的已发布版本比"
  fi
  consider_tag "$PREV_TAG"
  if [ -z "$PREV_CODE" ]; then
    abort "读不到 $PREV_TAG 的 versionCode，无法确认版本号增长（历史不完整？）"
  fi
else
  CANDIDATE_TAGS="$(git tag --list 'v[0-9]*' 2>/dev/null | grep -vxF -- "$TAG" || true)"
  for candidate in $CANDIDATE_TAGS; do
    consider_tag "$candidate"
  done
  # 任何一个正式标签的 versionCode 读不出都要中止（M8）：只把它记进 UNREADABLE_TAGS 再继续，
  # 等于把「有一个更高的发布版本我读不出来」当成「没有更高的版本」。
  if [ -n "${UNREADABLE_TAGS# }" ]; then
    abort "这些正式标签的 versionCode 读不到（${UNREADABLE_TAGS# }）：已发布的上界无法确认，不能放行（补齐标签历史后重试）"
  fi
fi

if [ -z "$PREV_CODE" ]; then
  if [ -z "${CANDIDATE_TAGS:-}" ]; then
    abort "找不到任何前序版本标签，无法确认 versionCode 增长；本仓不适用「首个发布」例外，因此不跳过检查（补标签历史，或本地用 --previous-tag 指定）"
  fi
  abort "前序标签的 versionCode 都读不到（${UNREADABLE_TAGS# }），无法确认版本号增长（历史不完整？）"
fi
if [ "$PREV_TAG_FOUND" = "$TAG" ]; then
  abort "前序版本只能取到本次发布的标签本身（$TAG）：拿不到已发布的上界"
fi
if [ "$VERSION_CODE" -le "$PREV_CODE" ]; then
  abort "versionCode 没有增长：本版 $VERSION_CODE 不大于 $PREV_TAG_FOUND 的 $PREV_CODE"
fi
pass "versionCode $VERSION_CODE 大于已发布最高 $PREV_TAG_FOUND 的 $PREV_CODE"

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
# 只接受**一条**完整合法的指纹（建议修改第 1 条）：注释行不参与比较，出现两个不同的值就中止。
# 否则文件里留一行注释掉的旧指纹，闸门就会在没人注意的情况下拿它去比对。
# 指纹文件按「失败关闭」校验（建议修改第 1 条）：去掉注释行与空行之后必须**恰好一行**，
# 而且**整行**就是 64 位十六进制。65 位、两行相同、多行、夹带别的字符，一律中止。
FP_LINES="$(grep -vE '^[[:space:]]*(#|$)' "$CERT_FILE" | tr -d '\r')"
FP_COUNT="$(printf '%s\n' "$FP_LINES" | grep -c .)"
if [ "$FP_COUNT" -ne 1 ]; then
  abort "期望指纹文件必须恰好一行有效内容（实际 ${FP_COUNT} 行）：$CERT_FILE"
fi
EXPECTED_FP="$(printf '%s\n' "$FP_LINES" | tr 'A-F' 'a-f')"
case "$EXPECTED_FP" in
  *[!0-9a-f]*) abort "期望指纹那一行不是纯十六进制：$CERT_FILE" ;;
esac
if [ ${#EXPECTED_FP} -ne 64 ]; then
  abort "期望指纹长度必须是 64 位十六进制（实际 ${#EXPECTED_FP} 位）：$CERT_FILE"
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

# apksigner 不同版本、不同签名方案的写法不一样：v1/v2 是「Signer #1 certificate SHA-256 digest: …」，
# v3/v3.1 还可能写成「Signer (minSdkVersion=…, maxSdkVersion=…) certificate SHA-256 digest: …」。
# 只认第一种时，CI 上的 build-tools 37 数出 0 个签名者（2026-10-21 run 39599697019）。所以收下所有
# 「Signer … certificate SHA-256 digest」行，去重后必须恰好一个指纹：同一张证书在 v2、v3 里各出现
# 一次算一张；出现两张不同的证书（例如轮换过密钥）照样中止。
DIGEST_LINES="$(printf '%s\n' "$VERIFY_RAW" | tr -d '\r' | grep -E '^Signer.* certificate SHA-256 digest: *[0-9A-Fa-f]{64} *$' || true)"
UNIQUE_FPS="$(printf '%s\n' "$DIGEST_LINES" | grep -oE '[0-9A-Fa-f]{64} *$' | tr -d ' ' | tr 'A-F' 'a-f' | sort -u)"
SIGNER_COUNT="$(printf '%s\n' "$UNIQUE_FPS" | grep -cE '^[0-9a-f]{64}$' || true)"
if [ "$SIGNER_COUNT" -ne 1 ]; then
  # 把 apksigner 的原始输出打出来（最多 40 行）便于看清格式，但去掉证书持有人（DN）那几行。
  echo "[release-guard] apksigner 原始输出（已去掉 DN 行）：" >&2
  printf '%s\n' "$VERIFY_RAW" | tr -d '\r' | grep -v -i 'DN:' | head -40 >&2 || true
  abort "签名证书不是恰好一张（去重后 $SIGNER_COUNT 张）：$APK"
fi
ACTUAL_FP="$UNIQUE_FPS"

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