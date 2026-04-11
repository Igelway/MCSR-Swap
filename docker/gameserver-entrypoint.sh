#!/bin/bash
set -e

# Copy config template files (only if they don't exist)
echo "Setting up configuration..."
if [ -d "/opt/config-template" ]; then
  cp -rn /opt/config-template/* /data/ 2>/dev/null || true
fi

# Create symlinks for mods
echo "Setting up mod symlinks..."

# Remove old symlinks first
find /data/mods -type l -delete 2>/dev/null || true

# Function to create symlinks recursively
# Skip -dev.jar and -sources.jar files
create_symlinks() {
  local source_dir="$1"
  local target_dir="$2"

  mkdir -p "$target_dir"

  for file in "$source_dir"/*; do
    if [ -f "$file" ]; then
      local filename=$(basename "$file")
      # Skip dev and sources jars
      if [[ "$filename" == *-dev.jar ]] || [[ "$filename" == *-sources.jar ]]; then
        echo "  Skipping $filename (dev/sources)"
        continue
      fi
      local target_path="$target_dir/$filename"
      echo "  Symlinking $filename -> $target_path"
      ln -sf "$file" "$target_path"
    elif [ -d "$file" ]; then
      local dirname=$(basename "$file")
      create_symlinks "$file" "$target_dir/$dirname"
    fi
  done
}

# Symlink mods
create_symlinks "/opt/app-files/mods" "/data/mods"

echo "Starting Minecraft server..."

# PUID/PGID support (for itzg/minecraft-server compatibility)
if [ -n "$PUID" ]; then
  export UID="$PUID"
fi
if [ -n "$PGID" ]; then
  export GID="$PGID"
fi

# Start the Minecraft server using the base image's entrypoint
exec /start
