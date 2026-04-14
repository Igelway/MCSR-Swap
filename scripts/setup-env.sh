#!/usr/bin/env bash
# Prepares env, secrets, and data directories before starting Docker Compose.
# Usage: setup-env.sh [--playit]
set -euo pipefail

PLAYIT=false
for arg in "$@"; do
    [ "$arg" = "--playit" ] && PLAYIT=true
done

if [ -f ".env" ]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

case "${MINECRAFT_SERVER_EULA:-}" in
    [Tt][Rr][Uu][Ee]|[Yy][Ee][Ss]|[Oo][Nn]|1)
        echo "✓ Minecraft EULA already accepted"
        ;;
    *)
        if [ ! -t 0 ]; then
            echo "Minecraft EULA not accepted. Set MINECRAFT_SERVER_EULA to true, yes, on, or 1 first." >&2
            echo "See https://www.minecraft.net/eula" >&2
            exit 1
        fi
        echo "Minecraft servers require accepting the Mojang EULA:"
        echo "  https://www.minecraft.net/eula"
        printf "Do you accept the Minecraft EULA? [y/N] "
        read -r REPLY
        case "${REPLY,,}" in
            y|yes)
                scripts/set-env-var.sh MINECRAFT_SERVER_EULA true
                export MINECRAFT_SERVER_EULA=true
                echo "-> Saved MINECRAFT_SERVER_EULA=true to .env"
                ;;
            *)
                echo "EULA not accepted, aborting." >&2
                exit 1
                ;;
        esac
        ;;
esac

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
        echo "→ Get your key at: https://playit.gg → Add Agent → Docker"
        echo "  Use the SECRET_KEY value from the shown docker run / compose command."
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
