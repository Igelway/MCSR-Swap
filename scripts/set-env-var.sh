#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 <KEY> <VALUE> [ENV_FILE]" >&2
    exit 1
fi

KEY="$1"
VALUE="$2"
ENV_FILE="${3:-.env}"

if [[ ! "$KEY" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "Invalid env var name: $KEY" >&2
    exit 1
fi

touch "$ENV_FILE"

if grep -Eq "^[[:space:]]*${KEY}=" "$ENV_FILE"; then
    TMP_FILE="$(mktemp)"
    awk -v key="$KEY" -v value="$VALUE" '
        BEGIN { replaced = 0 }
        $0 ~ "^[[:space:]]*" key "=" {
            print key "=" value
            replaced = 1
            next
        }
        { print }
        END {
            if (!replaced) {
                exit 1
            }
        }
    ' "$ENV_FILE" > "$TMP_FILE"
    mv "$TMP_FILE" "$ENV_FILE"
else
    if [ -s "$ENV_FILE" ]; then
        printf '\n' >> "$ENV_FILE"
    fi
    printf '%s=%s\n' "$KEY" "$VALUE" >> "$ENV_FILE"
fi
