#!/usr/bin/env bash
set -euo pipefail

# scripts/agent-check-md.sh
# Detect root-level markdown documents not registered in .agent/index.md.
# Safe for macOS (bash 3.2+) and Linux.

INDEX_FILE=".agent/index.md"

if [[ ! -f "$INDEX_FILE" ]]; then
    echo "Error: $INDEX_FILE not found." >&2
    exit 1
fi

unregistered=()

while IFS= read -r -d '' file; do
    rel="${file#./}"
    if ! grep -Fq "\`$rel\`" "$INDEX_FILE"; then
        unregistered[${#unregistered[@]}]="$rel"
    fi
done < <(find . -name "*.md" \
    -not -path "./.agent/*" \
    -not -path "./.git/*" \
    -not -path "./node_modules/*" \
    -not -path "*/node_modules/*" \
    -not -path "./target/*" \
    -not -path "./.claude/*" \
    -not -path "./release/*" \
    -not -path "./.cache/*" \
    -not -path "./.downloads/*" \
    -not -path "./.stage-worker*" \
    -not -path "*/dist/*" \
    -not -path "*/build/*" \
    -not -path "./.DS_Store" \
    -print0)

count=${#unregistered[@]}

if [[ $count -eq 0 ]]; then
    echo "All markdown files are registered in $INDEX_FILE."
    exit 0
else
    echo "Unregistered markdown files found (not in $INDEX_FILE):"
    for i in "${!unregistered[@]}"; do
        echo "  - ${unregistered[$i]}"
    done
    exit 1
fi
