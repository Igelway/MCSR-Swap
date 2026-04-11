#!/bin/bash
set -e

# Runtime PUID/PGID support: create/adjust minecraft user if running as root
PUID=${PUID:-1000}
PGID=${PGID:-1000}
RUN_AS_USER=""
if [ "$(id -u)" -eq 0 ]; then
  # ensure group and user exist with requested IDs
  if ! getent group minecraft >/dev/null 2>&1; then
    groupadd -g "${PGID}" minecraft || true
  else
    groupmod -g "${PGID}" minecraft || true
  fi
  if ! id -u minecraft >/dev/null 2>&1; then
    useradd -u "${PUID}" -g "${PGID}" -m -s /bin/sh minecraft || true
  else
    usermod -u "${PUID}" -g "${PGID}" minecraft || true
  fi
  chown -R minecraft:minecraft /data /opt/app-files /opt/config-template 2>/dev/null || true
  RUN_AS_USER="minecraft"
else
  RUN_AS_USER=$(id -un)
fi

# Copy config template files (only if they don't exist)
echo "Setting up configuration..."
if [ -d "/opt/config-template" ]; then
  mkdir -p /data
  cp -rn /opt/config-template/* /data/ 2>/dev/null || true
fi

# Write forwarding.secret from VELOCITY_SECRET env var (always overwrite to stay in sync)
if [ -n "${VELOCITY_SECRET:-}" ]; then
  mkdir -p /data
  printf '%s' "$VELOCITY_SECRET" > /data/forwarding.secret
  echo "→ forwarding.secret written from VELOCITY_SECRET"
else
  echo "Warning: VELOCITY_SECRET not set – Velocity forwarding secret may be missing!"
fi

# Note: MCSRSWAP_GAMESERVER_IMAGE is read directly by the plugin at runtime

# Create symlinks for JARs/plugins
echo "Setting up application file symlinks..."

# Remove old symlinks first
find /data -type l -delete 2>/dev/null || true

# Function to create symlinks recursively
create_symlinks() {
  local source_dir="$1"
  local target_dir="$2"

  mkdir -p "$target_dir"

  for file in "$source_dir"/*; do
    if [ -f "$file" ]; then
      local filename=$(basename "$file")
      local target_path="$target_dir/$filename"
      echo "  Symlinking $filename -> $target_path"
      ln -sf "$file" "$target_path"
    elif [ -d "$file" ]; then
      local dirname=$(basename "$file")
      create_symlinks "$file" "$target_dir/$dirname"
    fi
  done
}

# Symlink velocity.jar
ln -sf /opt/app-files/velocity.jar /data/velocity.jar

# Symlink plugins
create_symlinks "/opt/app-files/plugins" "/data/plugins"

echo "Starting Velocity..."
JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"
cd /data
# Run JVM as minecraft when possible
if [ "$(id -u)" -eq 0 ]; then
  exec su -s /bin/sh minecraft -c "exec \"$JAVA_BIN\" -Xms1G -Xmx1G -jar /data/velocity.jar"
else
  exec "${JAVA_BIN}" -Xms1G -Xmx1G -jar /data/velocity.jar
fi
