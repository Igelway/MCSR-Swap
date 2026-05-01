#!/bin/sh
set -e

# Read Velocity forwarding secret from Docker secret file
SECRET_FILE="${SECRET_FILE:-/run/secrets/forwarding_secret}"
if [ -f "$SECRET_FILE" ]; then
    SECRET=$(cat "$SECRET_FILE")
else
    SECRET="${FORWARDING_SECRET:-change_me}"
fi

# NanoLimbo v1.8.1 always reads settings.yml from the current working
# directory (hardcoded Paths.get("./")). Write it to /app/ (the WORKDIR).
cat > /app/settings.yml << EOF
bind:
  ip: '0.0.0.0'
  port: 25565

maxPlayers: -1

ping:
  description: '{"text": "MCSR-Swap"}'
  version: 'NanoLimbo'
  protocol: -1

dimension: THE_END

playerList:
  enable: false
  username: 'NanoLimbo'

headerAndFooter:
  enable: false

gameMode: 3

brandName:
  enable: false

joinMessage:
  enable: false

bossBar:
  enable: false

title:
  enable: false

infoForwarding:
  type: MODERN
  secret: '$SECRET'

readTimeout: 30000
debugLevel: 1

netty:
  useEpoll: true
  threads:
    bossGroup: 1
    workerGroup: 2

traffic:
  enable: true
  maxPacketSize: 8192
  interval: 7.0
  maxPacketRate: 500.0
EOF

exec java -jar /app/NanoLimbo.jar
