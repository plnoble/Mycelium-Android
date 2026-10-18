#!/usr/bin/env bash
# Full check for Linux / CI. Mirrors scripts/check-all.ps1: same task contract, same failure rules,
# same summary. Local development on this machine uses the PowerShell script (offline, local
# llama.cpp clone); this one is what android-ci.yml runs on the runner.
#
# Failure rules (exit code 1):
#   * Gradle exit code != 0 (failing tests, compile errors, lint errors, ...)
#   * compile warnings, lint warnings or Gradle deprecation warnings in the output
#   * failing or errored test cases
#   * an expected task did not run, or an expected report / APK is missing (a missing report is
#     never counted as zero)
#
# llama.cpp's own sources and headers are pinned third-party code, so their warnings are exempt
# (see llm/README.md); lines whose path contains llm/vendor are counted separately.
#
# Usage:
#   scripts/check-all.sh [--online] [--skip-llama-prepare] [upstream-url]
#
#   --online               let Gradle resolve dependencies (the runner has network; local runs must
#                          stay offline because Maven Central handshakes fail on this machine)
#   --skip-llama-prepare   llm/vendor/llama.cpp is already prepared
#   upstream-url           passed to scripts/prepare-llama.sh (default: the official repository)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/build/check-all"
ONLINE=0
SKIP_LLAMA=0
UPSTREAM=""
TASKS=(test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest)

while [ $# -gt 0 ]; do
  case "$1" in
    --online) ONLINE=1 ;;
    --skip-llama-prepare) SKIP_LLAMA=1 ;;
    *) UPSTREAM="$1" ;;
  esac
  shift
done

fail() {
  echo "check-all: $1" >&2
  exit 1
}

# --- 1. environment ----------------------------------------------------------

[ -n "${JAVA_HOME:-}" ] || fail "JAVA_HOME 未设置"
[ -x "$JAVA_HOME/bin/java" ] || fail "JAVA_HOME 下没有 bin/java：$JAVA_HOME"

ANDROID_HOME_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME_DIR" ] || fail "ANDROID_HOME（或 ANDROID_SDK_ROOT）未设置"
[ -d "$ANDROID_HOME_DIR" ] || fail "ANDROID_HOME 不是目录：$ANDROID_HOME_DIR"

# --- 2. module list (to check the reports and artifacts that must exist) ------

SETTINGS="$ROOT/settings.gradle.kts"
[ -f "$SETTINGS" ] || fail "settings.gradle.kts 不存在"

MODULES=()
while IFS= read -r module; do
  [ -n "$module" ] && MODULES+=("$module")
done < <(grep -o 'include([^)]*)' "$SETTINGS" | grep -o '":[^"]*"' | tr -d '"' | sort -u)

[ "${#MODULES[@]}" -gt 0 ] || fail "settings.gradle.kts 里没有解析到任何模块"

module_dir() {
  if [ "$1" = ":contract" ]; then echo "contract-lib"; else echo "${1#:}"; fi
}

module_kind() {
  local build="$ROOT/$(module_dir "$1")/build.gradle.kts"
  [ -f "$build" ] || fail "$1 的 build.gradle.kts 不存在：$build"
  if grep -q 'com\.android\.application' "$build"; then echo "app"
  elif grep -q 'com\.android\.library' "$build"; then echo "android-library"
  else echo "jvm"; fi
}

EXPECTED_TASKS=()
APP_MODULES=()
for module in "${MODULES[@]}"; do
  kind="$(module_kind "$module")"
  case "$kind" in
    app)
      APP_MODULES+=("$module")
      EXPECTED_TASKS+=("$module:testDebugUnitTest" "$module:lintDebug" "$module:lintRelease" \
        "$module:assembleDebug" "$module:assembleRelease" "$module:assembleDebugAndroidTest")
      ;;
    android-library) EXPECTED_TASKS+=("$module:testDebugUnitTest") ;;
    *) EXPECTED_TASKS+=("$module:test") ;;
  esac
done

# --- 3. llama.cpp source ------------------------------------------------------

VENDOR="$ROOT/llm/vendor/llama.cpp"
if [ "$SKIP_LLAMA" = "1" ]; then
  [ -d "$VENDOR" ] || fail "要求跳过准备，但 llm/vendor/llama.cpp 不存在"
else
  if [ -n "$UPSTREAM" ]; then
    bash "$ROOT/scripts/prepare-llama.sh" "$UPSTREAM" || fail "准备 llama.cpp 源码失败"
  else
    bash "$ROOT/scripts/prepare-llama.sh" || fail "准备 llama.cpp 源码失败"
  fi
fi

# --- 4. run Gradle ------------------------------------------------------------

mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/gradle.log"
SUMMARY="$LOG_DIR/summary.md"

ARGS=(--console=plain --warning-mode all)
[ "$ONLINE" = "1" ] || ARGS+=(--offline)

echo "check-all: 仓库 $ROOT"
echo "check-all: 跑 Gradle：gradlew ${ARGS[*]} ${TASKS[*]}"
# No pipe here: a pipe would hand us tee's exit code instead of Gradle's.
( cd "$ROOT" && ./gradlew "${ARGS[@]}" "${TASKS[@]}" ) > "$LOG" 2>&1
GRADLE_EXIT=$?

# --- 5. summary: task states, tests, lint, APKs, warnings --------------------

task_state() {
  local task="$1" state
  state="$(grep -E "^> Task $task( |\$)" "$LOG" | tail -1 | sed -E 's/^> Task [^ ]+ ?//')"
  if [ -z "$state" ]; then echo "没有运行"; else echo "$state"; fi
}

TOTAL_TESTS=0; TOTAL_FAILURES=0; TOTAL_ERRORS=0
TEST_TABLE=""
for dir in "$ROOT"/*/build/test-results/*/; do
  [ -d "$dir" ] || continue
  module="${dir#"$ROOT"/}"; module="${module%%/*}"
  [ "$module" = "contract-lib" ] && module=":contract" || module=":$module"
  task="$(basename "$dir")"
  tests=0; failures=0; errors=0; skipped=0
  for file in "$dir"TEST-*.xml; do
    [ -f "$file" ] || continue
    for attr in tests failures errors skipped; do
      value="$(grep -o "$attr=\"[0-9]*\"" "$file" | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"
      case "$attr" in
        tests) tests=$((tests + value)) ;;
        failures) failures=$((failures + value)) ;;
        errors) errors=$((errors + value)) ;;
        skipped) skipped=$((skipped + value)) ;;
      esac
    done
  done
  TEST_TABLE+="| $module | $task | $tests | $failures | $errors | $skipped |"$'\n'
  TOTAL_TESTS=$((TOTAL_TESTS + tests))
  TOTAL_FAILURES=$((TOTAL_FAILURES + failures))
  TOTAL_ERRORS=$((TOTAL_ERRORS + errors))
done

TOTAL_LINT_ERRORS=0; TOTAL_LINT_WARNINGS=0
LINT_TABLE=""
for file in "$ROOT"/*/build/reports/lint-results-*.txt; do
  [ -f "$file" ] || continue
  module="$(basename "$(dirname "$(dirname "$file")")")"
  [ "$module" = "contract-lib" ] && module=":contract" || module=":$module"
  variant="$(basename "$file" .txt)"; variant="${variant#lint-results-}"
  counts="$(grep -oE '[0-9]+ errors?, [0-9]+ warnings?' "$file" | tail -1)"
  if [ -n "$counts" ]; then
    errors="$(echo "$counts" | grep -oE '^[0-9]+')"
    warnings="$(echo "$counts" | grep -oE '[0-9]+ warnings?' | grep -oE '^[0-9]+')"
  else
    errors=0; warnings=0
  fi
  LINT_TABLE+="| $module | $variant | $errors | $warnings |"$'\n'
  TOTAL_LINT_ERRORS=$((TOTAL_LINT_ERRORS + errors))
  TOTAL_LINT_WARNINGS=$((TOTAL_LINT_WARNINGS + warnings))
done

APK_TABLE=""
APK_LIST="$(find "$ROOT" -path '*/build/outputs/apk/*' -name '*.apk' 2>/dev/null | sort)"
if [ -n "$APK_LIST" ]; then
  while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    size="$(wc -c < "$apk" | tr -d ' ')"
    APK_TABLE+="| $(basename "$apk") | $size 字节 | $apk |"$'\n'
  done <<< "$APK_LIST"
fi

# warnings: llama.cpp (llm/vendor) is exempt, everything else must be fixed
WARNING_PATTERN='(^w: |warning:|CMake Warning|has been deprecated|is deprecated|Deprecated Gradle features were used)'
WARNING_LINES="$(grep -nE "$WARNING_PATTERN" "$LOG" | grep -v 'llm/vendor' || true)"
EXEMPT_LINES="$(grep -nE "$WARNING_PATTERN" "$LOG" | grep 'llm/vendor' || true)"
WARNING_COUNT=0; [ -n "$WARNING_LINES" ] && WARNING_COUNT="$(echo "$WARNING_LINES" | wc -l | tr -d ' ')"
EXEMPT_COUNT=0; [ -n "$EXEMPT_LINES" ] && EXEMPT_COUNT="$(echo "$EXEMPT_LINES" | wc -l | tr -d ' ')"
ERROR_LINES="$(grep -E '^e: ' "$LOG" || true)"
ERROR_COUNT=0; [ -n "$ERROR_LINES" ] && ERROR_COUNT="$(echo "$ERROR_LINES" | wc -l | tr -d ' ')"

# --- 6. check that expected tasks ran and expected artifacts exist -----------

PROBLEMS=""
add_problem() { PROBLEMS+="- $1"$'\n'; }

[ "$GRADLE_EXIT" -eq 0 ] || add_problem "Gradle 退出码 $GRADLE_EXIT（构建或测试失败）"

for task in "${EXPECTED_TASKS[@]}"; do
  state="$(task_state "$task")"
  if [ "$state" = "没有运行" ]; then add_problem "预期任务没有运行：$task"
  elif [ "$state" = "FAILED" ]; then add_problem "任务失败：$task"; fi
done

for module in "${MODULES[@]}"; do
  dir="$ROOT/$(module_dir "$module")"
  if [ -d "$dir/src/test" ]; then
    if ! ls "$dir"/build/test-results/*/TEST-*.xml >/dev/null 2>&1; then
      add_problem "有测试源码但没有测试报告：$module"
    fi
  fi
done

for module in "${APP_MODULES[@]:-}"; do
  [ -n "$module" ] || continue
  dir="$ROOT/$(module_dir "$module")"
  for pair in "调试包:debug" "正式包:release" "测试包:androidTest/debug"; do
    name="${pair%%:*}"; path="${pair#*:}"
    if ! ls "$dir"/build/outputs/apk/"$path"/*.apk >/dev/null 2>&1; then
      add_problem "$module 缺少$name（build/outputs/apk/$path/）"
    fi
  done
  for variant in debug release; do
    suffix="$(echo "${variant:0:1}" | tr '[:lower:]' '[:upper:]')${variant:1}"
    state="$(task_state "$module:lint$suffix")"
    if [ "$state" != "没有运行" ] && [ "$state" != "SKIPPED" ] && [ ! -f "$dir/build/reports/lint-results-$variant.txt" ]; then
      add_problem "lint 任务跑过但没有报告：$module:lint$suffix"
    fi
  done
done

[ "$TOTAL_FAILURES" -eq 0 ] || add_problem "测试失败 $TOTAL_FAILURES 个"
[ "$TOTAL_ERRORS" -eq 0 ] || add_problem "测试出错 $TOTAL_ERRORS 个"
[ "$TOTAL_LINT_ERRORS" -eq 0 ] || add_problem "lint error $TOTAL_LINT_ERRORS 条"
[ "$TOTAL_LINT_WARNINGS" -eq 0 ] || add_problem "lint warning $TOTAL_LINT_WARNINGS 条"
[ "$WARNING_COUNT" -eq 0 ] || add_problem "输出里的警告 $WARNING_COUNT 条"

if [ -z "$PROBLEMS" ]; then
  RESULT="**通过**：0 失败、0 警告，退出码 0。"
else
  RESULT="**失败**："$'\n'"$PROBLEMS"
fi

{
  echo "# check-all 汇总（$(date '+%Y-%m-%d %H:%M:%S')）"
  echo
  echo "- 仓库：$ROOT"
  echo "- 提交：$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo '未知')"
  echo "- 模式：$([ "$ONLINE" = "1" ] && echo '允许联网' || echo '离线 --offline')"
  echo "- JAVA_HOME：$JAVA_HOME"
  echo "- ANDROID_HOME：$ANDROID_HOME_DIR"
  echo "- Gradle 任务：${TASKS[*]}"
  echo "- Gradle 退出码：$GRADLE_EXIT"
  echo
  echo "## 单元测试"
  echo
  echo "| 模块 | 测试任务 | 用例 | 失败 | 出错 | 跳过 |"
  echo "|---|---|---|---|---|---|"
  printf '%s' "$TEST_TABLE"
  echo "| **合计** | | **$TOTAL_TESTS** | **$TOTAL_FAILURES** | **$TOTAL_ERRORS** | |"
  echo
  echo "## lint"
  echo
  echo "| 模块 | 变体 | error | warning |"
  echo "|---|---|---|---|"
  printf '%s' "$LINT_TABLE"
  echo "| **合计** | | **$TOTAL_LINT_ERRORS** | **$TOTAL_LINT_WARNINGS** |"
  echo
  echo "## 构建产物"
  echo
  echo "| APK | 大小 | 路径 |"
  echo "|---|---|---|"
  printf '%s' "$APK_TABLE"
  echo
  echo "## 警告"
  echo
  echo "- 需要处理的警告：$WARNING_COUNT 条"
  echo "- llama.cpp 自身的警告（约定豁免）：$EXEMPT_COUNT 条"
  echo "- Kotlin 编译诊断里的错误行：$ERROR_COUNT 条"
  echo
  echo "## 结果"
  echo
  echo "$RESULT"
  echo
  echo "完整 Gradle 输出：$LOG"
} | tee "$SUMMARY"

if [ -n "$WARNING_LINES" ]; then
  echo
  echo "== 需要处理的警告（最多 20 条） =="
  echo "$WARNING_LINES" | head -20
fi
if [ -n "$ERROR_LINES" ]; then
  echo
  echo "== 编译错误（最多 20 条） =="
  echo "$ERROR_LINES" | head -20
fi

if [ -z "$PROBLEMS" ]; then exit 0; else exit 1; fi