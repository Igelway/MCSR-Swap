#!/bin/bash
# This script is run by itzg/minecraft-server via COPY_CONFIG_DEST
# It configures FabricProxy with the Velocity forwarding secret

if [ -f "/run/secrets/forwarding.secret" ]; then
  echo "Configuring FabricProxy with Velocity secret..."
  mkdir -p /data/config/FabricProxy
  SECRET=$(cat /run/secrets/forwarding.secret)
  
  cat > /data/config/FabricProxy/FabricProxy.toml << TOML
# FabricProxy Configuration
hackOnlineMode = true
hackEarlySend = false
hackMessageChain = true

[velocity]
enabled = true
secret = "$SECRET"
onlineMode = true
TOML
  
  echo "FabricProxy configured successfully"
else
  echo "Warning: Velocity forwarding secret not found"
fi
