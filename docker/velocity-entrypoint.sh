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
  chown -R minecraft:minecraft /data/velocity /opt/app-files /opt/config-template 2>/dev/null || true
  RUN_AS_USER="minecraft"
else
  RUN_AS_USER=$(id -un)
fi

# Copy config template files (only if they don't exist)
echo "Setting up configuration..."
if [ -d "/opt/config-template" ]; then
  mkdir -p /data/velocity
  cp -rn /opt/config-template/* /data/velocity/ 2>/dev/null || true
fi

# Substitute environment variables in config.yml
if [ -f "/data/velocity/plugins/mcsrswap/config.yml" ] && [ -n "$MCSRSWAP_GAMESERVER_IMAGE" ]; then
  escaped_gameserver_image=$(printf '%s\n' "$MCSRSWAP_GAMESERVER_IMAGE" | sed 's/[&|]/\\&/g')
  sed -i "/^[[:space:]]*docker:[[:space:]]*$/,/^[^[:space:]]/ s|^\\([[:space:]]*image:[[:space:]]*\\).*|\\1\"$escaped_gameserver_image\"|" /data/velocity/plugins/mcsrswap/config.yml
fi

# Create symlinks for JARs/plugins
echo "Setting up application file symlinks..."

# Remove old symlinks first
find /data/velocity -type l -delete 2>/dev/null || true

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
ln -sf /opt/app-files/velocity.jar /data/velocity/velocity.jar

# Symlink plugins
create_symlinks "/opt/app-files/plugins" "/data/velocity/plugins"

echo "Starting Velocity..."
JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"
cd /data/velocity
# Run JVM as minecraft when possible
if [ "$(id -u)" -eq 0 ]; then
  exec su -s /bin/sh minecraft -c "exec \"$JAVA_BIN\" -Xms1G -Xmx1G -jar /data/velocity/velocity.jar"
else
  exec "${JAVA_BIN}" -Xms1G -Xmx1G -jar /data/velocity/velocity.jar
fi
