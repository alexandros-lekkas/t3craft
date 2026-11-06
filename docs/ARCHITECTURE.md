# Architecture

How T3 Craft talks to T3 Code, and where each piece runs. This page is kept short. The code is the detail.

## Streamed UI direction

The primary UI will stream the real T3 web app in-game. The laptop opens the full app; clicking an agent opens its thread in a floating window. The native panel remains the fallback. The backend architecture below supports world state and that fallback; it does not replace the web app.

## The big picture

There are two connections, and they point in opposite directions:

- **Minecraft → T3.** The mod reads your threads and acts on them. This is the main path.
- **Agents → Minecraft.** The mod runs a small MCP server so agents can look around your world and build.

```mermaid
flowchart LR
    subgraph MC["Minecraft (T3 Craft mod)"]
        UI["Panel · HUD · Office · Laptop"]
        State["T3State<br/>one model per machine"]
        MCPB["MCP backend<br/>(primary)"]
        LEG["Legacy backend<br/>(approvals · diffs · live)"]
        MCS["Minecraft MCP server<br/>127.0.0.1:25590/mcp"]
        UI <--> State
        State --> MCPB
        State --> LEG
    end

    subgraph T3["T3 Code on your computer"]
        PUB["Public MCP<br/>/mcp"]
        CP["Client protocol<br/>HTTP + WebSocket"]
        AG["Coding agents"]
    end

    MCPB -- "JSON-RPC over HTTP<br/>OAuth (PKCE)" --> PUB
    LEG -- "RPC + subscriptions" --> CP
    AG -- "minecraft_status · nearby_blocks<br/>say · run_command" --> MCS
```

## Minecraft → T3: MCP first, legacy as fallback

Since [#1](https://github.com/alexandros-lekkas/t3craft/pull/1), every operation goes through a shared backend interface with two implementations:

| | **Public MCP** (primary) | **Legacy client protocol** |
|:--|:--|:--|
| Used for | Listing threads and activity, sending prompts and queued sends, starting and stopping threads, answering questions, models | Permission approvals, checkpoint diffs and patches, live push updates |
| Transport | JSON-RPC over HTTP (`/mcp`), JSON or SSE replies, `Mcp-Session-Id`, pagination | HTTP snapshots and RPC dispatch, plus a WebSocket subscribing to `orchestration.subscribeShell` and `orchestration.subscribeThread` |
| Auth | OAuth with dynamic client registration, PKCE S256 and a temporary `127.0.0.1` callback | Pairing token exchanged at `/oauth/token` |
| Why | Documented and stable across T3 updates | The only way to get these features today |

**Each leg has its own health.** An MCP failure never disables the legacy leg, and a legacy failure never disables MCP. When there's no live socket, the mod polls.

**Zero setup (next).** The mod finds T3 Code running on the same computer and authorizes itself, so the player never sees a pairing link. A power-user override lives only in `config/t3craft.json`. See [Decisions](DECISIONS.md#2026-11-05--zero-setup-auto-connect-to-the-local-t3).

**What the game shows** mirrors T3's sidebar: active threads, a collapsed snoozed section, and settled threads hidden. Notifications fire for the focused thread and for threads you started in Minecraft, and each new approval or question pings once.

## Agents → Minecraft: the built-in MCP server

The mod serves `http://127.0.0.1:25590/mcp` on loopback while the game runs:

| Tool | What it does |
|:--|:--|
| `minecraft_status` | Where you are, what you're looking at, time and game mode |
| `minecraft_nearby_blocks` | Block counts around you |
| `minecraft_say` | Posts a message only you can see |
| `minecraft_run_command` | Runs a command as you and returns the reply. Every command shows in chat. |

## Where the mod runs

```mermaid
flowchart LR
    T3[("T3 Code")]
    SP["<b>Singleplayer</b><br/>client + integrated server<br/>Create T3 World, office at spawn"] --> T3
    CL["<b>Your client</b><br/>panel, HUD, your own office"] --> T3
    CL -. "plays on" .-> VS["Any server<br/>(prompts never go<br/>through server chat)"]
    DS["<b>Dedicated server with the mod</b><br/>shared office everyone sees"] --> T3
    P["Other players<br/>(mod optional)"] -. "join" .-> DS
```

| Mode | Entrypoint | Who connects to T3 | Office and residents |
|:--|:--|:--|:--|
| **Singleplayer** | `client` + `main` (the common entrypoint, next) | Your client | Placed by the integrated server during world load. Residents are client-side. |
| **Client on a vanilla server** | `client` | Your client | Built with commands where you have op. Residents only you can see. |
| **Dedicated server with the mod** | `server` | The server | Shared office with residents everyone sees, with or without the mod |

**The common entrypoint** (`T3CraftCommon`, planned) is the one structural change that the world features depend on. Today the integrated singleplayer server runs no T3 Craft code, so it can't place the office before you arrive. ([world spec](specs/t3-world-spec.md#8-residents-growth-and-constraints))

## Office data flow

```mermaid
flowchart LR
    T["Threads from T3State"] --> R["OfficeRoster<br/>8 stable desks per machine<br/>pins first"]
    R --> B["OfficeBrain<br/>desk · whiteboard · lounge"]
    L["Office layout<br/>(design A manifest)"] --> B
    B --> E["Residents<br/>(player characters, next)"]
```

Working agents sit at their desk. Agents that need you walk to the whiteboard. Finished agents go to the lounge and leave a [report book](DECISIONS.md#2026-11-05--keep-report-books). Desks stay the same across reconnects and restarts.

## Build and checks

JDK 25+ (27 works since [#2](https://github.com/alexandros-lekkas/t3craft/pull/2)). `./gradlew build` runs the fixture-free checks. CI also runs `officeCheck`, `connectionCheck`, `featureCheck`, `reliabilityCheck`, `mcpCheck` and `uiCheck` against legacy and protocol-2 fixtures. The full list is in the [0.5 reference](REFERENCE.md#build-and-develop).
