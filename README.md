<div align="center">

<!--
  ╔══════════════════════════════════════════════════════════════════════╗
  ║  HERO DEMO SLOT: swap the poster below for the real demo before launch ║
  ╚══════════════════════════════════════════════════════════════════════╝

  WHAT TO CAPTURE (10 s, one continuous take, no cuts if possible):
    0.0–2.0  First person on the spawn path, walking toward the office door (daylight, default seed).
    2.0–3.5  Inside: three agents type at the bench. One waits at the whiteboard with an orange "needs you" tag.
    3.5–5.0  Look at that agent and right-click. Its real T3 thread opens in a floating window.
    5.0–6.5  The card reads "Run command? ./gradlew test". Press Y, and the status flips to Working.
    6.5–9.0  Cut (or turn) to the window: blocks appear one by one as the agent builds (2× speed).
    9.0–10.0 A "Done" toast slides in. Hold for 1 s.

  CAPTURE SETTINGS: 1920×1080 at 60 fps, GUI scale 3, FOV 70, HUD on (we want the UI), muted test client,
  separate game dir. Staged demo threads only, no real thread titles. Smooth camera off for first person.

  EXPORT:
    • demo.mp4: H.264, ≤ 10 MB, no audio. Drag it into the GitHub README editor (or an issue). GitHub
      uploads it and gives you a https://github.com/user-attachments/assets/<id> URL. Paste that URL alone
      on its own line here; GitHub renders it as an inline autoplay-on-click player.
    • demo.gif: 960 px wide, 15 fps, ≤ 8 MB (gifski: `gifski --fps 15 --width 960 -o docs/images/launch/demo.gif frames/*.png`).
      Use it in place of the poster img below if you want it to loop with no click.
-->
<a href="https://github.com/alexandros-lekkas/t3craft/releases/latest">
  <img src="docs/images/office-exterior-day.png" alt="Design render of the T3 Craft office" width="100%">
</a>

# T3 Craft

**Status: in active development — some features shown are in progress.** The office images are design renders; a real demo will replace them.

### Your coding agents, living in your Minecraft world.

Your [T3 Code](https://t3.codes) agents become characters in an office at spawn.<br>
Walk up to one, click it, and answer it. You never alt-tab, and there's **nothing to set up.**

[![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-mod%20%2B%20modpack-DBD0B4?style=flat-square)](https://fabricmc.net/)
[![Works with T3 Code](https://img.shields.io/badge/works%20with-T3%20Code-111827?style=flat-square)](https://t3.codes)
[![Latest release](https://img.shields.io/github/v/release/alexandros-lekkas/t3craft?style=flat-square&color=4ade80)](https://github.com/alexandros-lekkas/t3craft/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-3B82F6?style=flat-square)](LICENSE)
<!-- After the Modrinth pages go live, uncomment:
[![Modrinth downloads](https://img.shields.io/modrinth/dt/t3craft-pack?style=flat-square&logo=modrinth&label=downloads&color=1bd96a)](https://modrinth.com/modpack/t3craft-pack)
-->

**[⬇ Get it](#-get-it)** · [What you get](#-what-you-get) · [How it works](#-how-it-works) · [FAQ](#-faq) · [Roadmap](docs/ROADMAP.md)

</div>

---

## ⬇ Get it

1. **Download the T3 Craft pack** from [**Releases**](https://github.com/alexandros-lekkas/t3craft/releases/latest) or [Modrinth](https://modrinth.com/modpack/t3craft-pack). It adds a *T3 Craft* installation to the official Minecraft Launcher and leaves your other installs alone.
2. **Open T3 Code**, then press **Play** on *T3 Craft*.
3. **Click _T3 World_.** You spawn facing your office, and your agents are already at their desks.

No pairing links, no commands, no settings screens. T3 Craft finds T3 Code on your computer and connects by itself.

<sub>Already run Fabric? Drop `t3craft-x.y.z.jar` and Fabric API into `mods/`. Needs Minecraft Java 26.3, Fabric Loader 0.19.5+ and Java 25+.</sub>

## ✨ What you get

<table>
<tr>
<td width="50%" valign="top">

<b>Click an agent to talk.</b> The planned experience streams its real T3 thread into a floating window. The laptop opens the full T3 web app; the native panel remains the fallback.
</td>
<td width="50%" valign="top">
<img src="docs/images/office-ground-floor.png" alt="Ground-floor cutaway: agents at the bench, one at the whiteboard">
<b>An office that shows the status.</b> Working agents sit at the bench. Agents that need you wait at the whiteboard. Finished ones head to the lounge.
</td>
</tr>
<tr>
<td valign="top">
<img src="docs/images/ingame-agent-build.jpg" alt="An agent built a floating beacon platform; a Done toast in the corner">
<b>They can build, too.</b> Ask for a beacon platform and watch it go up block by block. You get one quiet toast when it's done.
</td>
<td valign="top">
<img src="docs/images/world-new-t3-world.png" alt="The New T3 World screen">
<b>One click to a world.</b> A plains seed, chosen so the office sits flat and dry by a lake, 13 blocks from spawn.
</td>
</tr>
</table>

<p align="center"><img src="docs/images/agents-lineup.png" alt="Agent skins: Claude, Codex, Muse, Grok, Cursor, OpenCode and a fallback" width="100%"><br>
<sub>Agents look like players, not villagers. Each provider has its own outfits.</sub></p>

**Also in the box:** a T3 laptop you right-click for the full T3 web app · real checkpoint diffs · report books when work is done · snoozed and finished threads stay out of the way, just like in T3 Code · a light Vanilla+ pack (Sodium, Lithium, AppleSkin, Mouse Tweaks…) with no shaders and no bloat.

## 🧠 How it works

```mermaid
flowchart LR
  subgraph PC["Your computer"]
    T3["T3 Code<br/>(desktop app)"]
    A["Agents<br/>Claude · Codex · Cursor · …"]
    T3 <--> A
  end
  subgraph MC["Minecraft · Fabric"]
    M["T3 Craft mod"]
    O["Office at spawn<br/>agents = characters"]
    U["Streamed T3 web UI · laptop<br/>native panel fallback"]
    M --> O
    M --> U
  end
  T3 <-- "local MCP<br/>auto-connect" --> M
```

T3 Craft talks to the T3 Code already running on your machine over T3's public MCP. Your code, keys and prompts never go through a Minecraft server. Every thread becomes a character, and its state picks where the character stands. [Architecture →](docs/ARCHITECTURE.md)

## ❓ FAQ

<details><summary><b>Do I need T3 Code?</b></summary>

Yes. T3 Craft is a window into [T3 Code](https://t3.codes) (free and open source). Your agents and threads live there, and Minecraft shows them.
</details>

<details><summary><b>Which agents work?</b></summary>

Anything T3 Code runs: Claude Code, Codex, Cursor, OpenCode and more. Each provider gets its own look.
</details>

<details><summary><b>Does it work in multiplayer?</b></summary>

The mod is client-side, so you can use it on any server and your prompts stay private. Install it on the server too to get a shared village that everyone sees.
</details>

<details><summary><b>Is it safe? Can agents touch my world?</b></summary>

Agents act only through T3 Code, with the permissions you already gave them there. Commands still ask for approval unless you chose full access in T3. Building in the world uses ordinary game commands and stays inside your world.
</details>

<details><summary><b>Will it slow my game down?</b></summary>

It shouldn't. The pack ships Sodium and Lithium and no shaders, and the office is ordinary blocks and a handful of characters.
</details>

<details><summary><b>Mac, Windows, Linux?</b></summary>

The mod runs wherever Minecraft Java 26.3 runs. Auto-connect is tested on macOS first; Windows and Linux reports are very welcome.
</details>

<details><summary><b>Is this official?</b></summary>

No. It's a fan-made, MIT-licensed project and isn't affiliated with Mojang, Microsoft or T3 Tools / Ping. It's just made with love for both.
</details>

## 🤝 Contributing

Bugs, ideas, office builds and skins are all welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) (JDK 25, `./gradlew build`), then [open an issue](https://github.com/alexandros-lekkas/t3craft/issues/new/choose). Every product decision is written down in [DECISIONS.md](docs/DECISIONS.md), with the reason.

## ⭐ Star history

<a href="https://star-history.com/#alexandros-lekkas/t3craft&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=alexandros-lekkas/t3craft&type=Date&theme=dark">
    <img alt="Star history chart" src="https://api.star-history.com/svg?repos=alexandros-lekkas/t3craft&type=Date" width="600">
  </picture>
</a>

## 🙏 Credits

Built and maintained by **[Alexandros Lekkas](https://github.com/alexandros-lekkas)**.
T3 Craft continues **[maxwellyoung/t3craft](https://github.com/maxwellyoung/t3craft)** by Maxwell Young (MIT). Thank you for the foundation.
[T3 Code](https://t3.codes) is made by [Theo](https://github.com/t3dotgg) and the [Ping](https://github.com/pingdotgg) team.
Modpack mods belong to their authors. See [Credits](CREDITS.md), [third-party licenses](THIRD_PARTY_LICENSES.md) and the [provider trademark notice](docs/legal/provider-icons-NOTICE.md).

<sub>MIT licensed. NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Not affiliated with or endorsed by T3 Tools Inc.; "T3 Code" is theirs.</sub>

