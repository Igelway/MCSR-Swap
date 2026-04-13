#!/usr/bin/env bash
# Prepares secrets and data directories before starting Docker Compose.
# Usage: setup-env.sh [--playit]
set -euo pipefail

PLAYIT=false
for arg in "$@"; do
    [ "$arg" = "--playit" ] && PLAYIT=true
done

# Forwarding secret (Velocity ↔ backend servers)
if [ -f ".forwarding.secret" ]; then
    echo "✓ .forwarding.secret already exists"
else
    openssl rand -base64 12 | tr -d '+/=\n' > .forwarding.secret
    chmod 600 .forwarding.secret
    echo "→ Generated .forwarding.secret"
fi

# playit.gg agent key
if [ "$PLAYIT" = "true" ]; then
    if [ -f ".playit.secret" ]; then
        echo "✓ .playit.secret already exists, reusing it."
    else
        printf "Paste your playit.gg agent key: "
        read -r KEY
        if [ -z "${KEY}" ]; then
            echo "No key entered, aborting." >&2
            exit 1
        fi
        printf '%s' "${KEY}" > .playit.secret
        chmod 600 .playit.secret
        echo "→ Saved to .playit.secret"
    fi
fi

# Data directories
mkdir -p data/{velocity,lobby}
echo "✓ data directories ready"
