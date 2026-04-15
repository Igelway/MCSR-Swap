#!/usr/bin/env bash
# Prepares env, secrets, and data directories before starting Docker Compose.
# Usage: setup-env.sh [--playit]
set -euo pipefail

prompt_read() {
    local prompt="$1"
    local target_var="$2"

    if [ -r /dev/tty ]; then
        printf '%s' "$prompt" > /dev/tty
        IFS= read -r "$target_var" < /dev/tty
    elif [ -t 0 ]; then
        printf '%s' "$prompt"
        IFS= read -r "$target_var"
    else
        return 1
    fi
}

has_compose_profile() {
    local profiles="${COMPOSE_PROFILES:-}"
    local profile

    IFS=',' read -ra profile_list <<< "$profiles"
    for profile in "${profile_list[@]}"; do
        profile="${profile#"${profile%%[![:space:]]*}"}"
        profile="${profile%"${profile##*[![:space:]]}"}"
        if [ "$profile" = "playit" ]; then
            return 0
        fi
    done

    return 1
}

append_compose_profile() {
    local profile_to_add="$1"
    local profiles="${COMPOSE_PROFILES:-}"

    if [ -z "$profiles" ]; then
        printf '%s' "$profile_to_add"
    else
        printf '%s,%s' "$profiles" "$profile_to_add"
    fi
}

PLAYIT=false
for arg in "$@"; do
    [ "$arg" = "--playit" ] && PLAYIT=true
done

if [ ! -f ".env" ]; then
    if [ -f ".env.example" ]; then
        cp .env.example .env
        echo "→ Created .env from .env.example"
    else
        touch .env
        echo "→ Created empty .env"
    fi
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

if has_compose_profile; then
    PLAYIT=true
fi

MINECRAFT_SERVER_EULA="${MINECRAFT_SERVER_EULA:-}"
case "${MINECRAFT_SERVER_EULA,,}" in
    true|yes|on|1)
        echo "✓ Minecraft EULA already accepted"
        ;;
    *)
        echo "Minecraft servers require accepting the Mojang EULA:"
        echo "  https://www.minecraft.net/eula"
        if ! prompt_read "Do you accept the Minecraft EULA? [y/N] " REPLY; then
            echo "Minecraft EULA not accepted. Set MINECRAFT_SERVER_EULA to true, yes, on, or 1 first." >&2
            echo "See https://www.minecraft.net/eula" >&2
            exit 1
        fi
        case "${REPLY,,}" in
            y|yes)
                scripts/set-env-var.sh MINECRAFT_SERVER_EULA true
                export MINECRAFT_SERVER_EULA=true
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
        if ! prompt_read "Paste your playit.gg agent key: " KEY; then
            echo "playit.gg is enabled, but .playit.secret is missing." >&2
            echo "Create .playit.secret with your agent key, rerun this command interactively, or disable playit by removing --playit / removing playit from COMPOSE_PROFILES." >&2
            exit 1
        fi
        if [ -z "${KEY}" ]; then
            echo "No key entered, aborting." >&2
            exit 1
        fi
        printf '%s' "${KEY}" > .playit.secret
        chmod 600 .playit.secret
        echo "→ Saved to .playit.secret"

        if ! has_compose_profile; then
            if ! prompt_read "Always start playit.gg with just up from now on? You can always set COMPOSE_PROFILES=playit in .env. [y/N] " REPLY; then
                REPLY=n
            fi
            case "${REPLY,,}" in
                y|yes)
                    COMPOSE_PROFILES="$(append_compose_profile playit)"
                    export COMPOSE_PROFILES
                    scripts/set-env-var.sh COMPOSE_PROFILES "$COMPOSE_PROFILES"
                    ;;
            esac
        fi
    fi
fi

# Data directories
mkdir -p data/{velocity,lobby}
echo "✓ data directories ready"
