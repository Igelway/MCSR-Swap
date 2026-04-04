#!/bin/bash
set -e

# Copy config template files (only if they don't exist)
echo "Setting up configuration..."
if [ -d "/opt/config-template" ]; then
  cp -rn /opt/config-template/* /data/ 2>/dev/null || true
fi

# Substitute environment variables in config.yml
if [ -f "/data/plugins/mcsrswap/config.yml" ] && [ -n "$MCSRSWAP_GAMESERVER_IMAGE" ]; then
  sed -i "s|gameServerImage:.*|gameServerImage: \"$MCSRSWAP_GAMESERVER_IMAGE\"|g" /data/plugins/mcsrswap/config.yml
fi

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
exec java -Xms1G -Xmx1G -jar /data/velocity.jar
