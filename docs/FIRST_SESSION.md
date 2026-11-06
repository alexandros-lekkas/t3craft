# Your first T3 Craft session

This is an early Fabric mod, not a replacement for T3 Code. Use a disposable Minecraft world for your first try: building the office changes blocks.

## Before you start

- Minecraft Java 26.3 with the T3 Craft pack (or Fabric Loader 0.19.5+, Fabric API and the jar from [Releases](https://github.com/alexandros-lekkas/t3craft/releases/latest)).
- T3 Code running on the same computer. That's the whole setup: there is no pairing link and no settings screen.

## The useful first five minutes

1. Start T3 Code, then launch Minecraft. Click **T3 World** beside Singleplayer and press **Create**: you arrive on the plaza facing your office, and within a few seconds chat says *T3 connected · press ` to open*. (In any other world, run `/t3 office` instead.)
2. Press **`**. The laptop opens: your active threads on the left, the selected one on the right. Pick an existing harmless thread, type a small request you would ordinarily make in T3 and press **Enter**: it sends and you are back in the game.
3. When the agent finishes or needs you, you get one toast and a chime. Press **`** within a minute to land on that thread.
4. Walk in. Working agents type at their desks; one that needs you waits at the whiteboard. Click an agent and a small card opens beside it, with its last message, an input box and Approve / Deny when it needs you. Type `/t3` for the two things you can do from chat. `/t3 office` brings you back to the door from anywhere.
5. Optional: add the mod's MCP server to your agent (`claude mcp add --transport http -s user minecraft http://127.0.0.1:25590/mcp`) and ask it to build something small near the office. Agent building is on by default; every command it runs shows in your chat.

## If something fails

- **"Not connected to T3 Code yet."** T3 Code isn't running, or isn't on this computer. Start it; the mod retries every 15 seconds.
- **The office is empty.** The mod found T3 but could not pair (the log says why, for example T3's bundled CLI was missing); it keeps trying, backing off to every five minutes.
- **Agents can't reach Minecraft.** The port moves if 25590 was taken (another game, a dev client). The address in use is in `config/t3craft-mcp.json` and in the log line `Minecraft MCP server on http://127.0.0.1:<port>/mcp`.

A useful issue report includes mod version, Minecraft/Fabric versions, T3 version, operating system, the step that failed and a redacted screenshot. Never include a token, private conversation or full unreviewed config/log.

## Tell us whether it earns its place

Did it connect without you doing anything? Did a real ping save an app switch? Did you reopen it on another day without being reminded? Those answers matter more than a star.
