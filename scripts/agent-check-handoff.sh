#!/usr/bin/env bash
# scripts/agent-check-handoff.sh
#
# Generic handoff health-check for the .agent/ context system.
# Portable across macOS and Linux bash. No jq, node, python required.
# No dependency on Claude Code, Codex, or any specific CLI / model / hook payload.
#
# Safe to call from:
#   - Claude Code hooks (Stop / PostToolUse)
#   - Codex hooks
#   - Manual shell invocation
#
# This script ONLY READS files under .agent/. It never writes or modifies them.
#
# Exit codes:
#   0   all checks passed
#   1   one or more checks failed (missing file, empty stub, no next step,
#       no state signal, no context signal, etc.)

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

AGENT_DIR=".agent"
CONTEXT_FILE="$AGENT_DIR/context.md"
STATE_FILE="$AGENT_DIR/state.md"
NEXT_FILE="$AGENT_DIR/next.md"

PASS=0
FAIL=0

ok()   { echo "  [OK]   $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
note() { echo "  [..]   $1"; }

# Count lines in $2 matching extended regex $1. Safe when file is missing
# or grep finds zero matches. Always echoes an integer.
count_matches() {
  local pattern="$1" file="$2" n
  if [[ ! -f "$file" ]]; then echo 0; return; fi
  n=$(grep -ciE "$pattern" "$file" 2>/dev/null || true)
  echo "${n:-0}"
}

# Count non-blank, non-heading lines (rough "content" measure).
content_lines() {
  local f="$1" n
  if [[ ! -f "$f" ]]; then echo 0; return; fi
  n=$(grep -cE '^[[:space:]]*[^#[:space:]]' "$f" 2>/dev/null || true)
  echo "${n:-0}"
}

# Portable mtime formatting (BSD stat on macOS, GNU stat on Linux).
mtime_of() {
  local f="$1"
  stat -f '%Sm' -t '%Y-%m-%d %H:%M' "$f" 2>/dev/null \
    || stat -c '%y' "$f" 2>/dev/null \
    || echo "unknown"
}

echo "Agent handoff check"
echo "===================="
echo "project: $PROJECT_ROOT"
echo ""

# ───── 0. .agent/ directory itself ─────────────────────────────────────
if [[ ! -d "$AGENT_DIR" ]]; then
  echo "  [FAIL] $AGENT_DIR/ directory does not exist."
  echo ""
  echo "Handoff check failed. Update .agent/context.md, .agent/state.md, and .agent/next.md before stopping or switching models."
  exit 1
fi

# ───── 1. File existence ───────────────────────────────────────────────
echo "[1/4] file existence"
for f in "$CONTEXT_FILE" "$STATE_FILE" "$NEXT_FILE"; do
  if [[ -f "$f" ]]; then
    ok "$f exists (modified $(mtime_of "$f"))"
  else
    bad "$f is MISSING"
  fi
done
echo ""

# ───── 2. Not empty / not just a stub ──────────────────────────────────
echo "[2/4] file is not empty or a stub"
for f in "$CONTEXT_FILE" "$STATE_FILE" "$NEXT_FILE"; do
  [[ -f "$f" ]] || continue
  c=$(content_lines "$f")
  if (( c < 3 )); then
    bad "$f has only $c content lines — appears empty or stub"
  else
    ok "$f has $c content lines"
  fi
done
echo ""

# ───── 3. TODO density ─────────────────────────────────────────────────
echo "[3/4] TODO density"
for f in "$CONTEXT_FILE" "$STATE_FILE" "$NEXT_FILE"; do
  [[ -f "$f" ]] || continue
  todo=$(count_matches '(^|[[:space:]])TODO([[:space:]:)]|$)' "$f")
  total=$(content_lines "$f")
  if (( total > 0 )) && (( todo > 0 )) && (( todo * 2 > total )); then
    bad "$f is TODO-heavy ($todo TODO lines / $total content lines)"
  else
    note "$f has $todo TODO line(s) of $total content"
  fi
done
echo ""

# ───── 4. Semantic content ─────────────────────────────────────────────
echo "[4/4] semantic content"

# next.md — must contain an actionable next step.
if [[ -f "$NEXT_FILE" ]]; then
  has_action=0
  next_heading_re='^##+[[:space:]]*(Immediate Action|Next( Step| Steps)?|Action(s)?|To Do|To-Do|具体动作|当前动作|下一步|接下来)'
  if grep -qE "$next_heading_re" "$NEXT_FILE"; then
    start=$(grep -nE "$next_heading_re" "$NEXT_FILE" | head -1 | cut -d: -f1)
    section=$(tail -n +"$((start + 1))" "$NEXT_FILE" | awk '/^##+ /{exit}{print}')
    if echo "$section" | grep -qE '^[[:space:]]*([-*]|[0-9]+[.)])[[:space:]]+\S'; then
      has_action=1
    fi
  fi
  # Fallback: any actionable list item anywhere in the file.
  if (( has_action == 0 )); then
    if grep -qE '^[[:space:]]*([-*]|[0-9]+[.)])[[:space:]]+\S' "$NEXT_FILE"; then
      has_action=1
    fi
  fi
  if (( has_action == 1 )); then
    ok "$NEXT_FILE has an actionable next step"
  else
    bad "$NEXT_FILE has no actionable next step (no list item / no recognizable Next section)"
  fi
fi

# state.md — must contain progress, test-result, or recovery signal.
if [[ -f "$STATE_FILE" ]]; then
  signals=0
  signals=$(( signals + $(count_matches '(\bDone\b|\bCompleted\b|\bIn Progress\b|\bBlocked\b|已完成|进行中|卡点|当前进度|进度)' "$STATE_FILE") ))
  signals=$(( signals + $(count_matches '(test|Test|测试|mvn[[:space:]]+test|npm[[:space:]]+test|npm[[:space:]]+run|pytest|cargo[[:space:]]+test|go[[:space:]]+test)' "$STATE_FILE") ))
  signals=$(( signals + $(count_matches '(recover|Recovery|how to resume|恢复|继续|回到上一步)' "$STATE_FILE") ))
  signals=$(( signals + $(count_matches '\[[xX ]\]' "$STATE_FILE") ))
  if (( signals >= 1 )); then
    ok "$STATE_FILE contains progress / test / recovery signals ($signals match(es))"
  else
    bad "$STATE_FILE has no progress / test result / recovery signals"
  fi
fi

# context.md — must mention current task or current state.
if [[ -f "$CONTEXT_FILE" ]]; then
  signals=0
  signals=$(( signals + $(count_matches '(current task|current state|now working|task:|goal:|context:|当前任务|当前状态|目标|背景|正在|目的)' "$CONTEXT_FILE") ))
  signals=$(( signals + $(count_matches '^##+[[:space:]]*(Task|Goal|Context|Current|Overview|任务|目标|背景|当前|概述)' "$CONTEXT_FILE") ))
  if (( signals >= 1 )); then
    ok "$CONTEXT_FILE describes current task or state ($signals match(es))"
  else
    bad "$CONTEXT_FILE does not mention current task or state"
  fi
fi
echo ""

# ───── Verdict ─────────────────────────────────────────────────────────
echo "===================="
echo "passed: $PASS    failed: $FAIL"
echo ""
if (( FAIL == 0 )); then
  echo "Handoff check passed. .agent handoff files look usable."
  exit 0
else
  echo "Handoff check failed. Update .agent/context.md, .agent/state.md, and .agent/next.md before stopping or switching models."
  exit 1
fi
