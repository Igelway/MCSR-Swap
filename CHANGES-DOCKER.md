# MCSR-Swap Docker Integration - Änderungszusammenfassung

## Was wurde geändert?

### 1. Neue Dateien

#### Docker Infrastructure
- **`docker-compose.yml`** - Startet Velocity + Lobby statisch
- **`Dockerfile.gameserver`** - Image für dynamische Game-Server
- **`.dockerignore`** - Optimiert Build-Context
- **`build-docker.sh`** - Automatisches Build-Script
- **`README-DOCKER.md`** - Komplette Docker-Dokumentation

#### Java Code
- **`DockerServerManager.java`** (~217 Zeilen)
  - Docker-API Integration (docker-java)
  - Container lifecycle: start, stop, cleanup
  - Automatische Velocity Server-Registrierung
  - Port-Management (25600-25650)
  - Health-Checks

### 2. Geänderte Dateien

#### `velocity-plugin/pom.xml`
```diff
+ docker-java-core (3.3.6)
+ docker-java-transport-httpclient5 (3.3.6)
```

#### `VelocitySwapPlugin.java`
- **Zeile 81**: `DockerServerManager` Feld hinzugefügt
- **Zeile 97**: `dockerManager` initialisiert in `onInit()`
- **Zeile 115-132**: Default-Config erweitert (docker-Sektion)
- **Zeile 145-149**: Docker-Config laden + initialisieren
- **Zeile 159-172**: `detectServers()` nutzt jetzt Docker-Manager wenn enabled

#### `WorldSwapCommands.java`
- **Zeile 14**: Import `Collectors` hinzugefügt
- **Zeile 51-123**: `cmdStart()` komplett überarbeitet:
  - Bei aktiviertem Docker: Player-Count ermitteln → Container spawnen → 15s warten → Game starten
  - Bei deaktiviertem Docker: Alte Logik (statische Server pingen)
- **Zeile 125-134**: `cmdStop()` stoppt jetzt auch Docker-Container

#### `.gitignore`
```diff
+ velocity-config/
+ lobby-data/
```

## Wie funktioniert es?

### Auto-Detection
Docker-Modus wird **automatisch aktiviert** wenn:
- Environment Variable `MCSRSWAP_DOCKER_MODE=true` gesetzt ist (in docker-compose.yml)
- **ODER** manuell in config.yml: `docker.enabled: true`

### Setup-Flow (Docker Mode)
1. `./build-docker.sh` - Baut alles + erstellt Config-Struktur
2. `docker-compose up -d` - Startet Velocity + Lobby (mit `MCSRSWAP_DOCKER_MODE=true`)
3. Plugin erkennt Docker automatisch
4. Player connecten zu `localhost:25577`

### Gameplay-Flow
1. Admin führt `/ms start` aus
2. **Velocity Plugin**:
   - Zählt aktive Spieler (ohne Spectators)
   - Ruft `dockerManager.startServers(playerCount)` auf
3. **DockerServerManager**:
   - Erstellt `playerCount` Container (game1, game2, ...)
   - Exposed Ports 25600, 25601, 25602, ...
   - Registriert Server in Velocity via `registerServer()`
   - Wartet 10s auf Server-Readiness
4. **Nach 15s Delay**: Game startet wie gewohnt
5. Bei `/ms stop`: Container werden gestoppt + entfernt

## Config-Beispiel

```yaml
# velocity-config/plugins/mcsrswap/config.yml
docker:
  enabled: false  # Wird überschrieben wenn ENV MCSRSWAP_DOCKER_MODE=true
  gameServerImage: mcsrswap-gameserver:latest
  network: mcsrswap-network
  minPort: 25600
  maxPort: 25650
  dataPath: ''  # Empty = auto-detect (XDG or ./server-data)
```

**Data Path Auto-Detection:**
1. **`dataPath: ''`** (leer/empty) → Automatische Erkennung:
   - **XDG_DATA_HOME** gesetzt? → `$XDG_DATA_HOME/mcsrswap/servers`
   - **Sonst HOME** gesetzt? → `$HOME/.local/share/mcsrswap/servers`
   - **Fallback**: `./server-data/` (relativ zum Working Directory)
2. **`dataPath: './my-servers'`** → Relativ zum Working Directory
3. **`dataPath: '/absolute/path'`** → Absoluter Pfad

**Server Data Layout:**
```
# Beispiel mit XDG:
~/.local/share/mcsrswap/servers/
├── game1/          # World data für Server 1
│   ├── world/
│   ├── world_nether/
│   ├── world_the_end/
│   └── server.properties
├── game2/          # World data für Server 2
└── game3/          # etc.

# Beispiel relativ (./server-data):
./server-data/
├── game1/
├── game2/
└── game3/
```

Lobby-Server nutzt `./lobby-data/` (im docker-compose.yml definiert).

**Auto-Detection:** Wenn du `docker-compose.yml` verwendest, ist Docker automatisch aktiviert (via `MCSRSWAP_DOCKER_MODE=true`).

## Zwei Modi

### Classic Mode (Standard)
- **Aktivierung:** Keine Docker-ENV, `docker.enabled: false` in config
- **Was:** Wie bisher - du richtest Game-Server manuell ein
- **Wann:** Wenn du volle Kontrolle willst oder kein Docker nutzen kannst
- **Setup:** Game-Server in `velocity.toml` eintragen, Plugin findet sie automatisch
- **Verhalten:** `/ms start` pingt vorhandene Server und startet das Spiel

### Docker Mode (Automatisch in Docker)
- **Aktivierung:** ENV `MCSRSWAP_DOCKER_MODE=true` ODER `docker.enabled: true` in config
- **Was:** Plugin spawnt automatisch Docker-Container bei `/ms start`
- **Wann:** Für einfaches Setup und automatische Skalierung
- **Setup:** Nur Velocity + Lobby laufen statisch (via docker-compose.yml)
- **Verhalten:** `/ms start` zählt Spieler → spawnt X Container → startet Spiel

## Vorteile

✅ **Einfaches Setup**: Zwei Modi - manuell (Standard) oder Docker  
✅ **Dynamische Skalierung**: Nur so viele Server wie Spieler (Docker-Modus)  
✅ **Ressourcen-Effizienz**: Container werden nach `/ms stop` entfernt (Docker-Modus)  
✅ **Isolation**: Jeder Game-Server läuft in eigenem Container (Docker-Modus)  
✅ **Rückwärtskompatibel**: `docker.enabled: false` (Standard) → exakt wie vorher  
✅ **Platform-agnostic**: Classic-Modus läuft überall (Windows/Linux/Mac)

## Einschränkungen

⚠️ **Linux-Only**: Benötigt `/var/run/docker.sock` Mount  
⚠️ **Port-Limit**: Max. 51 gleichzeitige Game-Server (25600-25650)  
⚠️ **Startup-Zeit**: ~15-20s bis Server bereit sind  
⚠️ **RAM**: Jeder Container nutzt ~2GB (konfigurierbar)  
⚠️ **Disk Space**: Jeder Game-Server benötigt ~500MB-1GB für World-Daten (Standard: `./server-data/` oder `~/.local/share/mcsrswap/servers/`)

## Testing

Noch nicht getestet! Vor Produktion:

1. Build-Prozess testen: `./build-docker.sh`
2. Stack starten: `docker-compose up -d`
3. Logs prüfen: `docker logs -f mcsrswap-velocity`
4. `/ms start` mit 2-3 Spielern testen
5. Container prüfen: `docker ps`
6. Rotation testen (nach Timer)
7. `/ms stop` testen → Container müssen weg sein

## Troubleshooting

**"Cannot connect to Docker daemon"**
→ Docker läuft nicht oder `/var/run/docker.sock` nicht gemounted

**"Port already in use"**
→ `minPort`/`maxPort` anpassen in config.yml

**Server starten nicht**
→ `docker logs mcsrswap-game1` prüfen  
→ Fabric-Mod korrekt in `/mods/` kopiert?

**Velocity findet Server nicht**
→ Netzwerk prüfen: `docker network inspect mcsrswap-network`

## Nächste Schritte

1. Code kompilieren + testen
2. Maven/Gradle Dependencies prüfen (docker-java evtl. shaden?)
3. Resource-Limits in docker-compose.yml setzen
4. Persistent Volumes für World-Data überlegen
5. Health-Check-Intervalle tunen
6. Windows/Mac Support (Docker-in-Docker)
