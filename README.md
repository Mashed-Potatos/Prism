# Prism

A Minecraft server platform that manages multiple independent servers behind a single proxy. Drop one jar into a folder, create your subserver folders, and Prism handles everything automatically. Ports, authentication, player transfers, and a web admin panel included.

No manual proxy configuration. No startup scripts. No port management.

---

## Requirements

- Java 21 or higher
- A VPS or dedicated server with full process access
- Does not work on shared Minecraft hosting panels like PebbleHost or Aternos (Yet)

---

## Quick Start

1. Create a folder on your server and drop `prism.jar` inside it
2. Run Prism once to generate the config and default folders

```
java -jar prism.jar
```

3. Drop a Paper, Purpur, or any compatible server jar into each subserver folder named `server.jar`
4. Run Prism again and it launches everything automatically
5. Players connect to your server address on port 25565 as normal

---

## Folder Structure

Any folder next to `prism.jar` that contains a `server.jar` is automatically detected and launched as a subserver. Name them anything you want.

> The server jar inside each subserver folder must be named exactly `server.jar` or Prism will not detect it.

```
prism.jar
prism.yml
survival/
  server.jar
  plugins/
  world/
skyblock/
  server.jar
  plugins/
  world/
minigames/
  server.jar
  plugins/
  world/
```

Prism auto assigns ports to each subserver starting from 25566. Subserver configs are patched automatically before each launch so you never need to touch `server.properties` or `paper-global.yml` manually.

---

## Supported Minecraft Versions

- 1.21 and 1.21.1 (protocol 767)
- 1.21.2 and 1.21.3 (protocol 768)
- 1.21.11 (protocol 774)

Unknown protocol versions fall back to the closest supported table automatically.

---

## Configuration

`prism.yml` is generated on first boot. Every option is explained inline.

```yaml
proxy:
  bind: "0.0.0.0"
  port: 25565
  motd: "A Prism Network"
  max-players: 100
  online-mode: true

forwarding:
  mode: velocity-modern
  velocity-secret: ""

subservers:
  base-port: 25566

default-subserver: null

panel:
  enabled: true
  bind: "127.0.0.1"
  port: 8080
  username: "admin"
  password: ""

chat-groups: {}
```

The panel password and velocity secret are auto generated on first boot if left blank. The password is printed to the console once and saved to `prism.yml`.

Per subserver overrides are supported:

```yaml
subservers:
  base-port: 25566
  survival:
    port: 25566
    jar: "server.jar"
    java-args: ["-Xmx4G", "-Xms2G"]
    jar-args: ["nogui"]
```

---

## In-Game Commands

Players can switch between subservers using a single command.

```
/server <name>     Transfer to a different subserver
/server            List all available subservers
```

---

## Console Commands

The console defaults to the first registered subserver. Commands go directly to the active subserver. Use `/console` to switch context.

Command | Description
--- | ---
`help` | Show all available commands
`status` | Show all subservers with ports, status, and player counts
`stopall` | Gracefully shut down all subservers and Prism
`startall` | Start all registered subservers
`restartall` | Restart all subservers
`stop <name>` | Stop a specific subserver
`start <name>` | Start a specific subserver
`restart <name>` | Restart a specific subserver
`broadcast <message>` | Send a message to all players on all subservers
`transfer <player> <subserver>` | Move a player to a different subserver
`sync <from> <to> <all\|PluginName>` | Copy a plugin and its config between subservers
`/console <name>` | Switch the active console context

Every command echoes the destination subserver so you always know where it went.

---

## Plugin Sync

Copy plugins and their configs between subservers manually whenever you need to.

```
sync survival skyblock all
sync survival skyblock LuckPerms
```

No automatic syncing happens on startup. Each subserver manages its own plugins independently unless you explicitly sync.

---

## Web Admin Panel

Access the panel at `http://127.0.0.1:8080` after boot. The auto generated password is printed to the console on first boot and saved to `prism.yml`.

The panel includes a live dashboard with subserver status and player counts, start, stop, and restart controls per subserver, a live console per subserver with command input, a player list with transfer controls, and a broadcast form.

---

## Chat Groups

By default chat is global and all players across all subservers see each other's messages. You can restrict chat to specific groups of subservers in `prism.yml`.

```yaml
chat-groups:
  survival-chat:
    - survival
    - skyblock
  minigames-chat:
    - minigames
    - lobby
```

Leave `chat-groups` empty for global chat.

---

## Trial vs Unlimited

The source code available here is the trial version, permanently limited to 2 subservers. A TRIAL watermark appears on the server list when the cap is reached. All other features work fully.

The unlimited version with no subserver limit is available for purchase on BuiltByBit.

---

## Early Release

This is an early release of Prism. More features and improvements are on the way. If you want to help test or report bugs contact `_____potato` on Discord.
