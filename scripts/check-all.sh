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
#   * a lint report without a summary line (a report is never silently counted as clean)
#   * lint errors or lint warnings
#
# Lint hints (informational) are NOT a failure: they are counted and shown in the lint table, so
# both gates agree (ruling 2026-10-19). This mirrors scripts/check-all.ps1.
#
# llama.cpp's own sources and headers are pinned third-party code, so their warnings are exempt
# (see llm/README.md); lines whose path contains llm/vendor are counted separately.
#
# Usage:
#   scripts/check-all.sh [--online] [--skip-llama-prepare] [--tasks a,b,c] [upstream-url]
#   scripts/check-all.sh --self-test [--self-test-report FILE]...
#
#   --online               let Gradle resolve dependencies (the runner has network; local runs must
#                          stay offline because Maven Central handshakes fail on this machine)
#   --skip-llama-prepare   llm/vendor/llama.cpp is already prepared
#   --tasks                comma-separated Gradle selectors for a subset run (debugging only)
#   upstream-url           passed to scripts/prepare-llama.sh (default: the official repository)
#   --self-test            only run the lint summary parser / gate self test and exit (no Gradle,
#                          no environment check)
#   --self-test-report F   extra real lint report to parse in the self test (repeatable); a report
#                          whose summary line cannot be found fails the self test
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/build/check-all"
ONLINE=0
SKIP_LLAMA=0
UPSTREAM=""
SUBSET_TASKS=""
SELF_TEST=0
SELF_TEST_REPORTS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --online) ONLINE=1 ;;
    --skip-llama-prepare) SKIP_LLAMA=1 ;;
    --tasks) shift; SUBSET_TASKS="${1:-}" ;;
    --self-test) SELF_TEST=1 ;;
    --self-test-report) shift; SELF_TEST_REPORTS+=("${1:-}") ;;
    *) UPSTREAM="$1" ;;
  esac
  shift
done

fail() {
  echo "check-all: $1" >&2
  exit 1
}

# --- lint summary parsing (shared by the real run and by --self-test) ---------
#
# AGP's lint text report ends with either "No issues found." or a summary line. When informational
# issues exist the summary has a third group, "<n> errors, <n> warnings, <n> hints" (and "1 hint" /
# "1 warning" / "1 error" in the singular). Ruling 2026-10-19: only errors and warnings fail the
# gate; a hint is counted and printed in the lint table but is not a failure. A report without a
# summary line is never treated as clean (that silently-passing case is why the two gates disagreed).
# Prints "errors warnings hints parsed" (parsed is 1 only when a summary line was found).
lint_counts() {
  local file="$1" line errors warnings hints
  line="$(grep -E '^[[:space:]]*[0-9]+ errors?, [0-9]+ warnings?(, [0-9]+ hints?)?[[:space:]]*$' "$file" | tail -1)"
  if [ -z "$line" ]; then
    if grep -q 'No issues found' "$file"; then echo "0 0 0 1"; else echo "0 0 0 0"; fi
    return
  fi
  errors="$(echo "$line" | grep -oE '^[[:space:]]*[0-9]+' | grep -oE '[0-9]+')"
  warnings="$(echo "$line" | grep -oE '[0-9]+ warnings?' | grep -oE '^[0-9]+')"
  hints="$(echo "$line" | grep -oE '[0-9]+ hints?' | grep -oE '^[0-9]+')"
  [ -n "$hints" ] || hints=0
  echo "$errors $warnings $hints 1"
}

# Rewrites the summary line of a lint report so that it advertises a single informational hint. Used
# by the self test to prove with real report text that a hint cannot change the outcome.
lint_text_with_hint() {
  local file="$1" line errors warnings
  line="$(grep -E '^[[:space:]]*[0-9]+ errors?, [0-9]+ warnings?(, [0-9]+ hints?)?[[:space:]]*$' "$file" | tail -1)"
  if [ -n "$line" ]; then
    errors="$(echo "$line" | grep -oE '^[[:space:]]*[0-9]+' | grep -oE '[0-9]+')"
    warnings="$(echo "$line" | grep -oE '[0-9]+ warnings?' | grep -oE '^[0-9]+')"
    sed -E "s|^[[:space:]]*[0-9]+ errors?, [0-9]+ warnings?(, [0-9]+ hints?)?[[:space:]]*\$|${errors} errors, ${warnings} warnings, 1 hint|" "$file"
  else
    sed 's|^[[:space:]]*No issues found.*$|0 errors, 0 warnings, 1 hint|' "$file"
  fi
}

# One self-test case: the sample text arrives on stdin (a heredoc, like a real report), is written
# to a temporary file, parsed and compared with the expected outcome. "pass" is only possible for a
# parsed report with 0 errors and 0 warnings; an unparsed report must always come out as "fail".
SELF_FAILURES=0
self_case() {
  local name="$1" want="$2" text tmp errors warnings hints parsed got
  text="$(cat)"
  tmp="$(mktemp)"
  printf '%s\n' "$text" > "$tmp"
  read -r errors warnings hints parsed <<< "$(lint_counts "$tmp")"
  rm -f "$tmp"
  got="fail"
  [ "$parsed" = "1" ] && [ "$errors" = "0" ] && [ "$warnings" = "0" ] && got="pass"
  if [ "$got" = "$want" ]; then
    printf 'PASS  %s\n' "$name"
  else
    printf 'FAIL  %s\n' "$name"
    SELF_FAILURES=$((SELF_FAILURES + 1))
  fi
  printf '      errors=%s warnings=%s hints=%s parsed=%s -> %s (expected %s)\n' \
    "$errors" "$warnings" "$hints" "$parsed" "$got" "$want"
}

run_lint_self_test() {
  local tmp_real tmp_hint r_errors r_warnings r_hints r_parsed h_errors h_warnings h_hints h_parsed r_got h_got report
  echo "check-all self test: lint summary parser and gate"
  echo

  # Real AGP wording, both number forms (singular and plural) and the missing summary line.
  self_case "hints only, singular (0 errors, 0 warnings, 1 hint)" pass <<'EOF'
0 errors, 0 warnings, 1 hint
EOF
  self_case "hints only, plural (0 errors, 0 warnings, 3 hints)" pass <<'EOF'
0 errors, 0 warnings, 3 hints
EOF
  self_case "hints only, no errors/warnings words (No issues found.)" pass <<'EOF'
No issues found.
EOF
  self_case "zero of everything (0 errors, 0 warnings, 0 hints)" pass <<'EOF'
0 errors, 0 warnings, 0 hints
EOF
  self_case "one warning, singular (0 errors, 1 warning, 0 hints)" fail <<'EOF'
0 errors, 1 warning, 0 hints
EOF
  self_case "one warning, singular, no hints group (0 errors, 1 warning)" fail <<'EOF'
0 errors, 1 warning
EOF
  self_case "two warnings, real AGP form (0 errors, 2 warnings)" fail <<'EOF'
0 errors, 2 warnings
EOF
  self_case "one error, singular (1 error, 0 warnings, 0 hints)" fail <<'EOF'
1 error, 0 warnings, 0 hints
EOF
  self_case "errors plus hints (2 errors, 0 warnings, 3 hints)" fail <<'EOF'
2 errors, 0 warnings, 3 hints
EOF
  self_case "errors and warnings plus hints (1 error, 4 warnings, 2 hints)" fail <<'EOF'
1 error, 4 warnings, 2 hints
EOF
  self_case "no summary line, issue text only" fail <<'EOF'
app/src/main/java/Foo.kt:12: Warning: unused [UnusedResources]
EOF
  self_case "empty report (no summary line)" fail <<'EOF'
EOF

  # Real report text: the summary line must be found and appending a hint must not change the
  # outcome. Everything happens in temporary copies; the repository is not touched.
  for report in ${SELF_TEST_REPORTS[@]+"${SELF_TEST_REPORTS[@]}"}; do
    if [ ! -f "$report" ]; then
      printf 'FAIL  real report not found: %s\n' "$report"
      SELF_FAILURES=$((SELF_FAILURES + 1))
      continue
    fi
    tmp_real="$(mktemp)"; tmp_hint="$(mktemp)"
    cp "$report" "$tmp_real"
    lint_text_with_hint "$report" > "$tmp_hint"
    read -r r_errors r_warnings r_hints r_parsed <<< "$(lint_counts "$tmp_real")"
    read -r h_errors h_warnings h_hints h_parsed <<< "$(lint_counts "$tmp_hint")"
    rm -f "$tmp_real" "$tmp_hint"
    r_got="fail"; [ "$r_parsed" = "1" ] && [ "$r_errors" = "0" ] && [ "$r_warnings" = "0" ] && r_got="pass"
    h_got="fail"; [ "$h_parsed" = "1" ] && [ "$h_errors" = "0" ] && [ "$h_warnings" = "0" ] && h_got="pass"
    if [ "$r_parsed" = "1" ] && [ "$h_parsed" = "1" ] && [ "$h_hints" -ge 1 ] && [ "$r_got" = "$h_got" ]; then
      printf 'PASS  real report: %s\n' "$report"
    else
      printf 'FAIL  real report: %s\n' "$report"
      SELF_FAILURES=$((SELF_FAILURES + 1))
    fi
    printf '      errors=%s warnings=%s hints=%s parsed=%s -> %s\n' \
      "$r_errors" "$r_warnings" "$r_hints" "$r_parsed" "$r_got"
    printf '      same text with "1 hint" in the summary: errors=%s warnings=%s hints=%s parsed=%s -> %s (must match)\n' \
      "$h_errors" "$h_warnings" "$h_hints" "$h_parsed" "$h_got"
  done

  echo
  if [ "$SELF_FAILURES" -eq 0 ]; then
    echo "check-all self test: PASS"
    exit 0
  fi
  echo "check-all self test: FAIL ($SELF_FAILURES case(s))" >&2
  exit 1
}

if [ "$SELF_TEST" = "1" ]; then
  run_lint_self_test
fi

# --- 1. environment ----------------------------------------------------------

[ -n "${JAVA_HOME:-}" ] || fail "JAVA_HOME 未设置"
[ -x "$JAVA_HOME/bin/java" ] || fail "JAVA_HOME 下没有 bin/java：$JAVA_HOME"

ANDROID_HOME_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME_DIR" ] || fail "ANDROID_HOME（或 ANDROID_SDK_ROOT）未设置"
[ -d "$ANDROID_HOME_DIR" ] || fail "ANDROID_HOME 不是目录：$ANDROID_HOME_DIR"

# --- 2. module list (defines the reports and artifacts that must exist) ------

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

# Module name from a path below the repository root (contract-lib maps to :contract).
module_of_path() {
  local relative="${1#"$ROOT"/}" first
  first="${relative%%/*}"
  if [ "$first" = "contract-lib" ]; then echo ":contract"; else echo ":$first"; fi
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

[ "${#APP_MODULES[@]}" -gt 0 ] || fail "没有找到 application 模块"

SUBSET=0
if [ -n "$SUBSET_TASKS" ]; then
  SUBSET=1
  IFS=',' read -r -a TASKS <<< "$SUBSET_TASKS"
else
  # "test" has no leading colon on purpose: it selects the same-named task in every subproject, so
  # a new module is covered without touching this script. The app tasks are derived from
  # settings.gradle.kts for the same reason.
  TASKS=(test)
  for module in "${APP_MODULES[@]}"; do
    TASKS+=("$module:lintDebug" "$module:lintRelease" "$module:assembleDebug" "$module:assembleRelease" "$module:assembleDebugAndroidTest")
  done
fi

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

# An executed task has no trailing state word, so an empty remainder means "executed" and a missing
# line means the task never took part in this run.
task_state() {
  local task="$1" line state
  line="$(grep -E "^> Task $task( |\$)" "$LOG" | tail -1)"
  if [ -z "$line" ]; then echo "没有运行"; return; fi
  state="$(echo "${line#"> Task $task"}" | tr -d ' ')"
  if [ -z "$state" ]; then echo "executed"; else echo "$state"; fi
}

TOTAL_TESTS=0; TOTAL_FAILURES=0; TOTAL_ERRORS=0
TEST_TABLE=""
for dir in "$ROOT"/*/build/test-results/*/; do
  [ -d "$dir" ] || continue
  module="$(module_of_path "${dir%/}")"
  task="$(basename "$dir")"
  # Reports are only read for tasks that took part in this run: a report left over from an earlier
  # run must never be counted.
  [ "$(task_state "$module:$task")" != "没有运行" ] || continue
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

TOTAL_LINT_ERRORS=0; TOTAL_LINT_WARNINGS=0; TOTAL_LINT_HINTS=0
LINT_TABLE=""
LINT_UNPARSED=""
for file in "$ROOT"/*/build/reports/lint-results-*.txt; do
  [ -f "$file" ] || continue
  module="$(module_of_path "$file")"
  variant="$(basename "$file" .txt)"; variant="${variant#lint-results-}"
  suffix="$(echo "${variant:0:1}" | tr '[:lower:]' '[:upper:]')${variant:1}"
  [ "$(task_state "$module:lint$suffix")" != "没有运行" ] || continue
  read -r errors warnings hints parsed <<< "$(lint_counts "$file")"
  # No summary line: never silently counted as clean (fail, like the PowerShell gate does).
  [ "$parsed" = "1" ] || LINT_UNPARSED+="$file"$'\n'
  LINT_TABLE+="| $module | $variant | $errors | $warnings | $hints |"$'\n'
  TOTAL_LINT_ERRORS=$((TOTAL_LINT_ERRORS + errors))
  TOTAL_LINT_WARNINGS=$((TOTAL_LINT_WARNINGS + warnings))
  TOTAL_LINT_HINTS=$((TOTAL_LINT_HINTS + hints))
done

APK_TABLE=""
APK_PATHS=""
for module in "${APP_MODULES[@]}"; do
  dir="$ROOT/$(module_dir "$module")/build/outputs/apk"
  [ -d "$dir" ] || continue
  while IFS= read -r apk; do
    [ -n "$apk" ] || continue
    size="$(wc -c < "$apk" | tr -d ' ')"
    APK_TABLE+="| $(basename "$apk") | $size 字节 | $apk |"$'\n'
    APK_PATHS+="$apk"$'\n'
  done < <(find "$dir" -name '*.apk' | sort)
done

# warnings: llama.cpp (llm/vendor) is exempt, everything else must be fixed
WARNING_PATTERN='(^w: |warning:|Warning:|CMake Warning|has been deprecated|is deprecated|Deprecated Gradle features were used)'
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

for task in $(grep -oE '^> Task :[^ ]+ FAILED' "$LOG" | sed -E 's/^> Task (:[^ ]+) FAILED/\1/' | sort -u); do
  add_problem "任务失败：$task"
done

if [ "$SUBSET" = "0" ]; then
  for task in "${EXPECTED_TASKS[@]}"; do
    state="$(task_state "$task")"
    if [ "$state" = "没有运行" ]; then add_problem "预期任务没有运行：$task"; fi
  done

  for module in "${MODULES[@]}"; do
    dir="$ROOT/$(module_dir "$module")"
    if [ -d "$dir/src/test" ]; then
      if ! ls "$dir"/build/test-results/*/TEST-*.xml >/dev/null 2>&1; then
        add_problem "有测试源码但没有测试报告：$module"
      fi
    fi
  done

  for module in "${APP_MODULES[@]}"; do
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
fi

[ "$TOTAL_FAILURES" -eq 0 ] || add_problem "测试失败 $TOTAL_FAILURES 个"
[ "$TOTAL_ERRORS" -eq 0 ] || add_problem "测试出错 $TOTAL_ERRORS 个"
# lint 的提示（hint / informational）只计数、只进汇总表，不算失败（2026-10-19 裁定）；error 和
# warning 照旧失败。
if [ -n "$LINT_UNPARSED" ]; then
  while IFS= read -r report; do
    [ -n "$report" ] && add_problem "lint 报告没有汇总行：$report"
  done <<< "$LINT_UNPARSED"
fi
[ "$TOTAL_LINT_ERRORS" -eq 0 ] || add_problem "lint error $TOTAL_LINT_ERRORS 条"
[ "$TOTAL_LINT_WARNINGS" -eq 0 ] || add_problem "lint warning $TOTAL_LINT_WARNINGS 条"
[ "$WARNING_COUNT" -eq 0 ] || add_problem "输出里的警告 $WARNING_COUNT 条"

if [ -z "$PROBLEMS" ]; then
  RESULT="**通过**：0 失败、0 警告，退出码 0（lint hint $TOTAL_LINT_HINTS 条，仅提示，不算失败）。"
else
  RESULT="**失败**："$'\n'"$PROBLEMS"
fi

{
  echo "# check-all 汇总（$(date '+%Y-%m-%d %H:%M:%S')）"
  echo
  echo "- 仓库：$ROOT"
  echo "- 提交：$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo '未知')"
  echo "- 工作区：$([ -z "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ] && echo '干净' || echo '有未提交改动')"
  echo "- 运行：$([ "$SUBSET" = "1" ] && echo '子集（不是完整门禁）' || echo '完整门禁')"
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
  echo "| 模块 | 变体 | error | warning | hint |"
  echo "|---|---|---|---|---|"
  printf '%s' "$LINT_TABLE"
  echo "| **合计** | | **$TOTAL_LINT_ERRORS** | **$TOTAL_LINT_WARNINGS** | **$TOTAL_LINT_HINTS** |"
  echo
  echo "- lint hint（提示，仅计数、不算失败）：$TOTAL_LINT_HINTS 条"
  echo
  echo "## 构建产物"
  echo
  echo "| APK | 大小 | 路径 |"
  echo "|---|---|---|"
  printf '%s' "$APK_TABLE"
  echo
  echo "## 关键任务状态"
  echo
  echo "| 任务 | 状态 |"
  echo "|---|---|"
  for task in "${EXPECTED_TASKS[@]}"; do
    echo "| $task | $(task_state "$task") |"
  done
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