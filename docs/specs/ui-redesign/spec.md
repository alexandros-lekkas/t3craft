> **Status:** implementation spec, 2026-11-05. The owner override at the end (no Connections screen, auto-connect) wins over anything above it. Pictures: `docs/design/ui.md`. Open `mockup.html` locally for the interactive version.

> **Current direction:** the real T3 web UI is streamed in-game. The laptop opens the full app; clicking an agent opens its thread in a floating window. Native-panel designs below are earlier explorations and fallback references.

# T3 panel redesign: implementation spec

Target: `T3Screen` and friends on `origin/fix/ui-screens` (PR #3), Minecraft 26.3, Fabric loader 0.19.5.
Companion files: `mockup.html` (interactive, with GUI-scale and laptop toggles) and `mockup.png`.

**Units.** Every metric below is in **GUI pixels**. At GUI scale 2, 1 GUI px = 2 physical px, so a 1920×1080 window is 960×540 GUI px. Fonts are sized in GUI px, so they scale with the GUI scale the way the vanilla font does.

---

## 0. What's wrong today (and the fix for each)

| Problem in the current panel | Cause in code | Fix |
|---|---|---|
| Text looks heavy and smeared | The vanilla 8 px bitmap font. On top of that, `withBold(true)` on headings, on the "You" and "Agent" labels and on `**bold**` spans. MC bold redraws each glyph 1 GUI px to the right (`GlyphInfo.getBoldOffset()` is 1.0 for every provider, TTF included). | Use Inter Regular for body text. Bold becomes a separate **Inter SemiBold face**, and `withBold` is never used (§2). |
| Dense, wall-of-text layout | Line advance = `font.lineHeight` (9). Text runs the full width (≈1500 physical px per line). No paragraph rhythm. | Line advance 12 (1.5×). A reading column of about 72 characters, centred. Gaps of 6 between paragraphs and 10 between messages (§4.3). |
| Rainbow syntax (yellow code, cyan links, green "Done", blue rows) | High-saturation colours everywhere, e.g. `CODE 0xF0C674` and green status text | One neutral grey ramp plus four desaturated status accents (§3). Inline code becomes a muted tan. |
| Everything has equal weight | Every message is a "You"/"Agent" label plus body. Tool rows and metadata are as bright as content. | User messages become right-aligned bubbles. Agent text has no label. Tool calls collapse to one muted line. Metadata uses the muted colour (§4.3). |
| Chunky beveled vanilla buttons in the sidebar | `Button.builder` × 5 | Flat custom `T3Button`s and an icon footer (§4.1, §6). |
| 60+ threads in the sidebar | All non-archived threads | Mirror T3's visibility: active + pinned only; snoozed collapsed; settled and subagents hidden (§4.1). |

---

## 1. Responsive frame

`PanelLayout` is computed once in `init()` (which also runs on every resize and GUI-scale change) from `width`, `height` and `Framing`. Nothing is recomputed per frame.

Let `W, H` = the GUI size of the framed area: the whole screen for `FULL`, the laptop's screen rect for `LAPTOP`.

| Breakpoint | Condition | Sidebar | Lineage rail | Notes |
|---|---|---|---|---|
| **Wide** | `W ≥ 880` | 164 | 150 + 8 gap | 1920@2 (960), 2560@2 (1280), 2560@3 (853 → Medium) |
| **Medium** | `600 ≤ W < 880` | 148 | hidden: becomes a header chip "3 agents ▾" that opens a popover | 1920@3 (640), 1280@2 (640), laptop at 1920@2 (≈716) |
| **Narrow** | `W < 600` | overlay: hidden by default and toggled with the ☰ button or **Tab** when the composer is empty. When open it is a 164-wide panel over the conversation with a 60% scrim. | chip | 854@2 (427), 1920@4 (480), 1366@3 (455) |
| **Short** | `H < 320` (additive) | – | – | Header 20, composer min 24 (1 line), approval card collapsed to 1 line with "Y/N ›" (expands on click), conversation keeps ≥ 3 lines |

Outer margin: 8 (`FULL`). The panel is one rounded rect (r = 4) filling `[8, 8, W-8, H-8]`. Sidebar and content are separated by a 1 px divider, not a gap. This matches the T3 desktop app, where the sidebar is a slightly lighter column of the same window.

Reading column: `colW = clamp(T3Fonts.charWidth × 72, 260, contentW − 2·24)`. With Inter at size 8, the measured `charWidth` is about 4.35, so `colW` ≈ 313. The column is centred in the content area: `colX = contentX + (contentW − colW)/2`. The composer and the cards use the **same column**, as in T3, so the user's eye never travels.

> The owner asked for ~70 chars. 72 × average advance gives that. Measure `charWidth` once per font epoch with `font.width(styled("the quick brown fox jumps over the lazy dog, THE QUICK…"))/len`. Don't hardcode it, because the font can fall back (§2.4).

---

## 2. Typography

### 2.1 What 26.3 actually provides (verified in `minecraft-merged-deobf-26.3.jar`)

- Font JSON lives at `assets/<ns>/font/<name>.json`, with a `providers` array. Provider types include `ttf`, `bitmap`, `space`, `unihex` and `reference`.
- The `ttf` provider is `net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition(Identifier location, float size, float oversample, Shift shift, String skip)`. Its JSON fields:
  - `file`: an Identifier resolved under `font/`, so `"t3craft:inter-regular.ttf"` → `assets/t3craft/font/inter-regular.ttf`
  - `size`: default 11, in GUI px
  - `oversample`: raster multiplier
  - `shift`: `[x, y]` in GUI px
  - `skip`: characters to skip so the next provider supplies them
- Rasterisation is FreeType via LWJGL (`TrueTypeGlyphProvider`), anti-aliased greyscale, baked lazily into the font atlas (`FontSet$DelayedBake`), and drawn with `RenderTypes.textGrayscale`.
- **`FontTexture` samples with `FilterMode.NEAREST`.** This is the important catch: a glyph rasterised at `oversample = k` is only crisp when drawn at GUI scale k. At any other scale it gets nearest-neighbour resampled and shimmers.
- Fonts are selected per span with `Style.withFont(FontDescription)`. In 26.3 the type is `net.minecraft.network.chat.FontDescription.Resource(Identifier id)`. There is no `ResourceLocation` any more; it is `net.minecraft.resources.Identifier`.
- `Font.lineHeight` is a single `final int` (9) for the whole `Font` and **does not change per FontDescription**. Line advance is ours to control: we never use `font.lineHeight` for layout.
- `Font.width(FormattedText|FormattedCharSequence)` and `Font.split(FormattedText, int)` respect the span's font, so measuring and wrapping work unchanged **as long as the Style carries the font**. `font.width(String)` does **not**: it measures in the default font.
- Vanilla `minecraft:uniform` is the Unifont-only font, the one behind "Force Unicode Font". It is thinner than `default`, but it is a 16 px bitmap downscaled ×0.5, jaggy and not proportional-nice. It is a zero-cost fallback (§2.4), not the goal.

### 2.2 Font assets to add

```
src/main/resources/assets/t3craft/font/
  inter-regular.ttf          # Inter 4.x, Latin + Latin-1 + General Punctuation + arrows subset (~70 KB)
  inter-semibold.ttf         # same subset (~72 KB)
  jbmono-regular.ttf         # JetBrains Mono, optional, migration step 8 (~45 KB subset)
  OFL.txt                    # SIL Open Font License 1.1 (both fonts are OFL)
  body_2.json  body_3.json  body_4.json      # Inter Regular, oversample = 2/3/4
  strong_2.json strong_3.json strong_4.json  # Inter SemiBold
  mono_2.json  mono_3.json  mono_4.json      # optional
```

Generate the variants for scales 1–6 if you want to cover "Auto" on 4K. They are cheap (§2.5).

`body_2.json`:

```json
{
  "providers": [
    { "type": "ttf", "file": "t3craft:inter-regular.ttf",
      "size": 8.0, "oversample": 2.0, "shift": [0.0, 0.5], "skip": "" },
    { "type": "reference", "id": "minecraft:include/default" },
    { "type": "reference", "id": "minecraft:include/unifont" }
  ]
}
```

- The **reference fallbacks are mandatory**. Without them, any glyph Inter's subset lacks (emoji, CJK, box drawing) renders as a missing-glyph box. With them, those glyphs drop to the vanilla font. That is a slight style mismatch, but it stays readable.
- `size` 8.0 is the body size. At scale 2 that is 16 physical px, slightly larger than T3 desktop's ~14–15 px body text, which suits viewing distance in-game.
- Tune `shift` y in-game so Inter's x-height baseline sits on the same row as vanilla text drawn at the same y. Start at 0.5; Inter's ascender is taller than MC's 7 px cap height.

Size scale (all Inter; "strong" = SemiBold face):

| Token | Face | Size | Advance (line height) | Use |
|---|---|---|---|---|
| `TITLE` | strong | 9 | 13 | Markdown h1/h2, empty-state title |
| `HEAD` | strong | 8 | 12 | h3–h6, card titles, header thread title |
| `BODY` | body | 8 | 12 | Agent text, bubbles, sidebar titles, composer placeholder |
| `META` | body | 7 | 10 | Sidebar status/time, tool rows, header meta, hints |
| `MONO` | mono (or vanilla `default` until step 8) | 7 | 10 | Code blocks, commands in cards |

Sizes 7 and 9 need their own JSONs (`meta_N.json` with `size: 7`, `title_N.json` with `size: 9`). That gives 5 roles × 3 scales = 15 tiny JSONs, generated by a Gradle task or written by hand.

### 2.3 How to render with it (exact code)

```java
// T3Fonts.java
static final Identifier BODY_2 = Identifier.fromNamespaceAndPath("t3craft", "body_2");
// …
static Style body()   { return Style.EMPTY.withFont(new FontDescription.Resource(id("body"))); }
static Style strong() { return Style.EMPTY.withFont(new FontDescription.Resource(id("strong"))); }
private static Identifier id(String role) {
    int s = Math.clamp((int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()), 2, 4);
    return Identifier.fromNamespaceAndPath("t3craft", role + "_" + s);
}

// usage
Component c = Component.literal(text).withStyle(T3Fonts.body().withColor(T3Theme.TEXT_BODY));
graphics.text(font, c, x, y, 0xFFFFFFFF, false);        // colour comes from the style; never pass shadow=true
List<FormattedCharSequence> rows = font.split(c, colW);  // wraps using Inter metrics
int w = font.width(c);                                   // NOT font.width(String)
```

- `T3Fonts` caches the six `Style` objects per `epoch`. The epoch bumps when the GUI scale changes (`init()` compares `getGuiScale()`) and on resource reload (register a Fabric `ResourceManagerHelper` client reload listener, or compare `minecraft.font` identity in `init()`). **Every layout cache keys on the epoch.**
- Never call `withBold(true)`: on TTF it smears by 1 GUI px. Use `strong()`. Italic (`withItalic`) is a shear and acceptable on Inter, so keep it.
- Markdown inline: `**x**` → `strong()` with `TEXT_PRIMARY`, `` `x` `` → `body()` (or `mono()`) with `CODE_INLINE`, links → `LINK` colour with **no underline**, and drop the " (url)" suffix unless the link text and URL differ *and* the URL fits on the same row.

### 2.4 Fallback ladder

1. Inter TTF at the matching oversample (default).
2. If the GUI scale is outside the generated set, use the nearest generated set. This is slightly soft at scale 5+, but readable.
3. If the T3 config `ui.font = "vanilla"` (an escape hatch: no dialog, only the config key and a `/t3 font vanilla|inter|uniform` command): body uses `FontDescription.DEFAULT` and strong uses `DEFAULT` with no bold. The layout still applies the new spacing and colours, so the redesign holds up even without the TTF.
4. `uniform` is available as `new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"))` for players who prefer it. It is lighter than `default` and costs nothing to ship.

### 2.5 Cost

- **Disk/jar:** about 150 KB for two subset Inter faces, plus 45 KB if mono is added.
- **Native memory:** each `ttf` provider instance opens its own `FT_Face` over a direct `ByteBuffer` copy of the file. For 5 roles × 3 scales = 15 providers, that is about 15 × 75 KB ≈ 1.1 MB, plus FreeType face structs (tens of KB each). Negligible.
- **Atlas:** glyphs bake lazily, only for characters actually drawn, at `size × oversample` px. At scale 4, a 32 px-tall glyph cell means ASCII for one role costs about 95 × 32×20 px, roughly 60 K texels, a fraction of one 256² page. Only the active scale's glyphs get baked in practice.
- **CPU:** the first draw of a new glyph rasterises it (FreeType, ~10–50 µs per glyph), a one-time hitch of ≤ 5 ms when the panel first opens. After that, drawing costs the same as vanilla text. `font.split` on TTF costs the same as on bitmap (advances are cached per glyph).
- **Reload:** F3+T or a resource-pack change rebuilds the font sets and adds about 20 ms. Bump the epoch.

---

## 3. Colour tokens (`T3Theme`)

ARGB ints. Neutral ramp tuned against `BG_PANEL`. Contrast ratios are vs `#0C0C0E`.

| Token | Value | Use | Contrast |
|---|---|---|---|
| `BG_PANEL` | `0xF20C0C0E` | Content area (95% opaque; the world barely shows through) | – |
| `BG_SIDEBAR` | `0xF2111114` | Sidebar column | – |
| `BG_RAISED` | `0xFF16161A` | Composer, cards, popovers, lineage card | – |
| `BG_BUBBLE` | `0xFF222227` | User bubble | – |
| `BG_CODE` | `0xFF121215` | Code blocks | – |
| `BG_HOVER` | `0x10FFFFFF` | Hover rows | – |
| `BG_SELECTED` | `0x1AFFFFFF` | Focused sidebar row | – |
| `BORDER` | `0xFF26262C` | Composer/card/chip 1 px outline | – |
| `DIVIDER` | `0x12FFFFFF` | Sidebar/content split, header underline | – |
| `TEXT_PRIMARY` | `0xFFEDEDF0` | Titles, bubble text, `**strong**` | 17:1 |
| `TEXT_BODY` | `0xFFCFCFD4` | Agent body text | 13:1 |
| `TEXT_SECONDARY` | `0xFFA1A1AA` | Project names, chip labels, tool row labels | 7.5:1 |
| `TEXT_MUTED` | `0xFF8A8A93` | Times, hints, placeholders, bullets | 5.5:1 |
| `TEXT_FAINT` | `0xFF55555E` | Disabled controls only | 2.6:1 (decorative) |
| `CODE_INLINE` | `0xFFD9B48F` | Inline code (muted tan; replaces `#F0C674`) | 9:1 |
| `CODE_TEXT` | `0xFFC9C9D1` | Code block text | 11:1 |
| `LINK` | `0xFF8AB4F8` | Links | 8.5:1 |
| `WORKING` | `0xFF6BA6F5` | Working status, spinner | – |
| `NEEDS_YOU` | `0xFFF0B254` | Needs you, approval accent | – |
| `DONE` | `0xFF5FC48A` | Done (sidebar dot only) and connected | – |
| `ERROR` | `0xFFEF7A7A` | Error and the stop button fill | – |
| `QUESTION` | `0xFF8AB4F8` | Question card accent | – |
| `APPROVAL_TINT` | `0x14F0B254` | Approval card wash over `BG_RAISED` | – |
| `SCRIM` | `0x99000000` | Narrow-mode sidebar overlay, laptop world dim | – |

Rules:

- Status colours appear **only** on dots, the spinner, the 2 px card accent bar and the status word. Never on body text.
- No text shadows anywhere (`shadow=false`).
- Never more than one accent colour in a single row.

---

## 4. Components (metrics at GUI scale 2, Wide breakpoint, 960×540)

```
x: 8                172 173                                         794 802      952
   ┌──────────────────┬─────────────────────────────────────────────────┬──────────┐ y 8
   │ T3 Code ●     ✎  │ aflekkas/t3craft / Use T3 Code…  ● Working 1m12s [Opus 5.5] │ 8..32  header 24
   │──────────────────│─────────────────────────────────────────────────│ Lineage  │
   │ aflekkas/t3craft │          ┌──────────────────────────┐           │ 3 running│
   │ ▌Use T3 Code… 25s│          │            user bubble ▐ │           │ ● Opus…  │
   │  ● Working   +3  │          ›_ Ran 3 commands ›                   │ ● Muse…  │
   │ Muse PR#3…    4m │          Agent reply text, 72ch column …       │ Previous │
   │  ● Needs you     │          ┌ approval card ──────────────┐       │  37 ›    │
   │ …                │          └──────────────────────────────┘      │          │
   │ Snoozed  6     › │          ┌ composer ────────────────────┐      │          │
   │ ⚑2  ⇄  ≡  ▣      │          └───────────────────────────────┘     │          │
   └──────────────────┴─────────────────────────────────────────────────┴──────────┘ y 532
```

### 4.1 Sidebar (`T3Sidebar`, x 8–172, width 164)

**Header (h 24):**

- "T3 Code": `HEAD`, `TEXT_PRIMARY`, at (16, 16).
- Connection dot: 4×4, r = 2 via stepped fills, 6 px after the wordmark, coloured `DONE`, `TEXT_MUTED` when connecting, `ERROR` when failed. Hovering it shows a tooltip with the error.
- Right-aligned: the "new thread" icon button (16×16 hit, a ✎ glyph in `TEXT_SECONDARY`; hover `BG_HOVER`; active `BG_SELECTED` while `newThread`).
- In Narrow mode, a ☰ button sits at the far left of the content header instead.

**Machine filter (h 16, only if > 1 machine):** a chip "All machines ▾" in `META`/`TEXT_SECONDARY`. Click cycles, as today. The per-project filter row is removed, because grouping replaces it.

**List body** (scrolls; pixel-based offset; 6 px top padding):

- **Project group header (h 18):** the project title as stored (no uppercase; caps read shouty at 7 px), `META`, `TEXT_MUTED`, at x+12. Right side: the count, in `TEXT_FAINT`. Click collapses the group (chevron ▸/▾ appears on hover only). Collapsed groups go in `T3Config.ui.collapsedProjects` (debounced save; perf item 5). When there is more than one machine and no filter, the label is "project · machine".
- **Thread row (h 30, inset 4 px L/R, r 3 on hover/selected):**
  - Line 1 (y + 6): title, `BODY`, `TEXT_PRIMARY` (or `TEXT_SECONDARY` when not focused and status is Idle/Done), ellipsized to `rowW − 12 − ageW − 6`. Right-aligned: age (`META`, `TEXT_MUTED`), formatted `now · 25s · 4m · 3h · 2d` from `updatedAt`, or from `workingSince` while working.
  - Line 2 (y + 18):
    - A status glyph at x+12: a 4×4 dot, or the 6×6 spinner (2 rotating dots, 8 frames, 100 ms) while Working.
    - Then a status word in `META` and the status colour: "Working", "Needs you", "Idle", "Done", "Error", "Offline".
    - Right side: a subagent badge "+3" (`META`, `TEXT_MUTED`, with a tiny ⑂ glyph) when the thread has running child threads.
    - A pin glyph ⚲ in `TEXT_MUTED` when pinned.
  - Selected row: `BG_SELECTED` plus a 2×14 px left bar in `TEXT_PRIMARY` at 60% alpha. Hover: `BG_HOVER`.
  - Needs-you rows get **no** row tint. The amber word is enough. This is what keeps the list calm.
- **Snoozed section** (pinned to the bottom of the list, above the footer):
  - Row h 22: "Snoozed" in `META`/`TEXT_SECONDARY`, the count in `TEXT_MUTED`, and a chevron ›/⌄. Click toggles it.
  - When expanded, snoozed rows render in the same row format but at 70% text alpha, with "until 3:00 PM" as the line-2 word. The list area shrinks to make room (max 40% of the sidebar height, scrolling inside).

**Footer (h 28, top divider):** four 24×20 flat icon buttons with tooltips:

| Button | Glyph | Action |
|---|---|---|
| Decisions | ⚑, plus a count badge (amber pill, `META`, dark text) when > 0 | `mod.openDecisions()` |
| Connections | ⇄ | Opens the connections screen |
| Activity | ≡ | Opens the activity screen (disabled when there's no focus) |
| Desk | ▣ | Click toggles the pin. Right-click (or Shift-click) opens `T3PinsScreen`. |

Tooltips include the bound key: "Decisions (J)". The text labels are dropped because the tooltips carry them; the badge keeps Decisions discoverable.

**Visibility rules (`T3SidebarModel.build`):**

```
isSubagent(t)  = t.raw.relationshipToParent == "subagent"  ||  t.raw.parentThreadId != null
isSettled(t)   = t.raw.settled == true
                 // legacy shell without the field: status ∈ {DONE, ERROR} && updatedAt < now − 30 min && t.id != focusedId
isSnoozed(t)   = t.raw.snoozed == true && (snoozedUntil == null || snoozedUntil > now)
shown(t)       = !isSubagent(t) && ( pinned(t) || (!isSettled(t) && !isSnoozed(t)) )
snoozedList(t) = !isSubagent(t) && isSnoozed(t) && !isSettled(t) && !pinned(t)
```

- Field names were verified against T3's thread payload (`settled`, `settledAt`, `snoozed`, `snoozedUntil`, `parentThreadId`, `relationshipToParent: "subagent"`). `T3State.row()` must copy these into `ThreadRow`. Add fields to `ThreadRow` rather than re-reading `raw` per frame, and parse `snoozedUntil` and `updatedAt` once (perf item 2).
- **The filter lives in the sidebar model only.** `T3State.threads()` keeps children and settled threads, so these keep working: `T3Decisions` (child approvals still reach Decisions), the HUD "waiting" count, the lineage rail, the village/office, and `/t3 open <id>`.
- The focused thread is always shown, even if settled or a subagent (it is reached via the rail, a ping or Decisions). It appears in its project group with a muted "settled" or "subagent of …" line-2 word.
- **Order:** projects are ordered by their most recent `updatedAt`. Threads within a project are ordered by `updatedAt` desc, which is T3's order. No re-sorting by status, so rows don't jump when a status flips.

### 4.2 Content header (`T3Header`, h 24, from x 173)

- **Left:** breadcrumb at (contentX + 14, 16).
  - Project in `BODY`/`TEXT_MUTED`, then " / " in `TEXT_FAINT`, then the thread title in `BODY`/`TEXT_PRIMARY`.
  - The breadcrumb ellipsizes the project first (to ≥ 40), then the title.
  - New-thread mode: "project / New thread".
- **Right**, laid out right-to-left with 6 px gaps:
  - Overflow `⋯` (16×16): a popover with "Review changes", "Activity", "Pin to desk", "Connections". Each runs immediately, with no confirmation.
  - Review: a flat text button, `META`/`TEXT_SECONDARY`, shown only when online with a focus.
  - Model chip (h 16, `BG_RAISED`, 1 px `BORDER`, r 3): a provider dot + model name in `META`/`TEXT_SECONDARY`. It opens the same picker as the composer chip; both chips are anchors of one picker.
  - Status: spinner + "Working 1m 12s" (`META`, `WORKING`). When Needs you: amber dot + "Needs you". Otherwise a dot + the status word in `TEXT_MUTED`. Offline: "Offline · last known".
  - Medium/Narrow: a lineage chip "⑂ 3 ▾" sits before the status.
- **Underline:** a 1 px `DIVIDER` at y 32.
- The 2-line status/meta header goes away. Step text (`row.step()`) moves into the conversation as the live "Working" line (§4.3).

### 4.3 Conversation (`T3Conversation`)

Area: y from 32 + 8 to the card/composer top − 8, x = the reading column (`colX`, `colW`). Scrolling is **pixel-based**: `scrollPx`, 36 px per wheel notch, clamped, and anchored to the bottom while at 0. This replaces the line-index scroll, so mixed line heights work. Only blocks intersecting the viewport are drawn (binary search on cumulative block tops).

The timeline is built from `turnItems` when present (they already arrive ordered and typed). Otherwise it is built from `messages` + `activity`.

**Blocks** (vertical gap 10 between blocks of different kinds, 6 within the same kind):

1. **User bubble.**
   - Right-aligned in the column; width = min(textW + 2·8, 0.8·colW). `BG_BUBBLE`, r 4 (stepped corners: 3 fills per corner), padding 8 h × 6 v.
   - Text in `BODY`/`TEXT_PRIMARY`, advance 12.
   - Long prompts are clamped to 8 rows with a "Show more" `META` link (click to expand; the state is kept in `expandedBlocks`).
   - Image attachments show as a 48×32 `BG_RAISED` tile labelled "image" in `META`.
2. **Agent text.** No label and no avatar: clean prose at the full column width.
   - `BODY`/`TEXT_BODY`, advance 12.
   - Paragraph gap 6.
   - Headings: `TITLE` (h1/h2) or `HEAD` (h3+), in `TEXT_PRIMARY`, 10 above and 4 below.
   - Bullets: "•" (`TEXT_MUTED`) at the indent, hanging indent 12 per level. Numbered lists right-align the number in a 12 px gutter.
   - Block quote: a 2 px `DIVIDER` bar plus text in `TEXT_SECONDARY`, indent 10.
   - Rule: a 1 px `DIVIDER` with 8 above and below.
   - Tables: cells joined with a 10 px gap in `TEXT_SECONDARY`, header row in `strong`. Wide tables wrap, as today.
3. **Code block.**
   - `BG_CODE`, 1 px `BORDER`, r 3, padding 8 × 6. Text in `MONO`/`CODE_TEXT`, advance 10.
   - A language label (`META`, `TEXT_MUTED`) at top-right if it is given.
   - Rows > 14 collapse with a "Show 23 more lines" footer row.
   - No horizontal scroll. Long lines wrap with a 12 px continuation indent.
4. **Tool group.** Each consecutive run of non-message items (command execution, file read/edit, search, MCP call) becomes **one row**.
   - Row: h 16, a glyph at column x (`›_` for commands, `◇` for edits, `⌕` for search, `⚙` for other) in `TEXT_MUTED`, then a summary in `META`/`TEXT_SECONDARY` and a chevron `›` in `TEXT_MUTED`. Hover: `BG_HOVER` across the column, r 3.
   - Summary text examples:
     - "Ran 3 commands"
     - "Edited 2 files"
     - "Ran 2 commands, read 4 files"
     - "Running `./gradlew build`…" (live, with the spinner instead of the glyph)
     - "1 command failed" (the word "failed" in `ERROR`)
   - Expanded (click or Enter while hovered): child rows h 12 indented 14, each item's title or command in `MONO`/`TEXT_SECONDARY` with an exit marker "✓" in `TEXT_MUTED` or "✗ 1" in `ERROR`. Clicking a child row toggles up to 12 lines of its output/detail in a code block.
   - Expansion state: `Set<String> expanded` keyed by the first item's id.
5. **Thinking / reasoning:** a collapsed row like a tool group: "Thought for 8s ›", in `META`/`TEXT_MUTED`.
6. **Working line** (only on the live turn, the last block): spinner + "Working for 1m 12s" in `META`/`TEXT_MUTED`. If `row.step()` is set, append it: "Working for 1m 12s · Running tests". This matches T3's "Working for 25s".
7. **Error notice:** `ERROR` 2 px left bar plus `META` text, inline at the end of the turn. The connection error banner stays as a dismiss-less 1-line strip under the header, in `ERROR` text on `0x14EF7A7A`.

**Streaming:** the caret is **not** part of the text. Draw a 1×9 `TEXT_MUTED` bar after the last glyph of the streaming block, with a blink at 530 ms. This means the layout cache isn't invalidated on every frame (it was invalidated by the `" ▍"` append before).

**Empty states** (centred in the column):

- No focus: "Pick a thread, or start a new one" in `HEAD`/`TEXT_SECONDARY`, with "✎ New thread" as a flat button underneath.
- No messages: "No messages yet" in `META`/`TEXT_MUTED`.

### 4.4 Approval card (`T3ApprovalCard`)

- Same column x and width as the composer; it sits 8 above the composer.
- Background `BG_RAISED` plus an `APPROVAL_TINT` wash, 1 px `BORDER`, r 4, and a 2 px `NEEDS_YOU` bar at the left inside edge.
- Padding 10 × 8.
- **Row 1 (h 12):**
  - Left: the title in `HEAD`/`TEXT_PRIMARY`, from `kind`: "Run command?", "Edit files?", "Use tool?".
  - Right: buttons.
    - **Approve** (h 16, padding 8, `TEXT_PRIMARY` fill at 92% with `0xFF0C0C0E` text, r 3, followed by the key hint "Y" in a muted inline glyph).
    - **Deny** (ghost: 1 px `BORDER`, `TEXT_SECONDARY` text, hint "N").
    - Clicks and keys act immediately. No confirmation, per the owner's preference.
- **Row 2 (META, `TEXT_MUTED`):** "Opus 5.5 wants to run this in aflekkas/t3craft". If the request comes from a **subagent** shown in this parent's view, it reads "from Opus: office details · subagent". This is how child approvals surface without child rows in the sidebar.
- **Body:** a code block (MONO) with the command or detail. Max 6 rows, then the card scrolls internally (as today).
- **Multiple pending:** a "1 of 3 ‹ ›" pager in `META` next to the title.
- **Height cap:** 40% of the conversation area. In Short mode the card collapses to row 1 with the command ellipsized inline.
- **After a click:** the card shows "Approved" or "Denied" in `META` for ≤ 1 s, or until the snapshot drops the request. This replaces the buttons, which stops double sends.
- **Question card (`T3QuestionCard`):** the same shell with the `QUESTION` accent.
  - Header: the question header and "1/3".
  - Options are rows h 16: a key badge "1" (a `BG_BUBBLE` 10×10 square with `META` text), then the label in `BODY`/`TEXT_PRIMARY`, then " — description" in `TEXT_MUTED`. Hover `BG_HOVER`.
  - Multi-select uses ☐/☑ glyphs instead of "[ ]" and "[x]".
  - The key logic is unchanged.

### 4.5 Composer (`T3Composer`)

- Column x/width, bottom at `H − 8 − 10`.
- Box: `BG_RAISED`, 1 px `BORDER` (focused: `0xFF3A3A42`), r 6, min h 44, grows to fit up to 6 text rows (h ≤ 44 + 5·12), then scrolls.
- **Text area:** inset 10 × 8. Placeholder in `BODY`/`TEXT_MUTED`:
  - Default: "Ask anything…"
  - New thread: "New thread in aflekkas/t3craft…"
  - Questions: "Press 1–4, or type your own answer…"
- **Bottom row** (inside the box, y = bottom − 8 − 16):
  - Left: hints in `META`/`TEXT_FAINT`→`TEXT_MUTED`: "Enter send · ⇧Enter stay · Esc play". Hidden below a 320-wide column.
  - Right: the **model chip** (h 16, no border, hover `BG_HOVER`), with a provider dot, the model name and effort ("Opus 5.5 Medium"), and ▾, in `META`/`TEXT_SECONDARY`. It turns `NEEDS_YOU`-amber text while a model is picked but not yet sent (the existing semantics).
  - **Send / stop button:** a 16×16 circle (stepped r 8).
    - Text present: `TEXT_PRIMARY` fill with a dark "↑".
    - Empty and the thread is Working: `ERROR` fill with a 6×6 dark square = **Stop**. Stop moves here from the header, as in T3.
    - Empty and idle: `TEXT_FAINT` fill (disabled).
- **Under the box:** nothing. The old footer line goes away because the hints moved inside.
- **Picker popover:** opens *upward* from the chip, width 200, `BG_RAISED`, 1 px `BORDER`, r 4.
  - Provider headers in `META`/`TEXT_MUTED`.
  - Model rows h 14 in `BODY`. A ✓ marks the selected model. "new thread" in `META`/`TEXT_MUTED` sits right-aligned on rows that need one.
  - Max height 60% of H.
  - Uses `graphics.nextStratum()` as today.

**Implementation:** keep `EditBox` (with `setBordered(false)`, `setTextColor(TEXT_PRIMARY)`, placed in the text area) for phase 1. It is single-line and uses the vanilla font, because `EditBox` positions the cursor with unstyled `font.width(String)` (see Risks). Migration step 8 replaces it with `T3TextArea` (multi-line, Inter, own cursor math using `font.width(styledPrefix)`). Until then, the box is fixed at min h 44 with the EditBox vertically centred in the top 28.

### 4.6 Lineage rail (`T3LineageRail`, Wide only; x 802–944)

A card (`BG_RAISED`, 1 px `BORDER`, r 6) at the top of the right gutter, y 40. Width 142, padding 10.

- **Row 1:** the machine label in `BODY`/`TEXT_PRIMARY` ("MacBook"). This is the focused thread's environment label.
- **Divider.**
- "Lineage · 3 running" in `META`/`TEXT_MUTED`.
- Rows (h 18): a provider dot (Claude `0xFFE08A5B`, Codex `0xFF9CA3AF`, other `0xFF8AB4F8`; these are colour dots, not brand logos), then the title in `META`/`TEXT_PRIMARY` (ellipsized), then the age right-aligned in `TEXT_MUTED`. It lists the running children first, then the parent ("↑ parent title") if the focused thread is itself a child.
- "Previous agents  37 ›" when there are settled children. Clicking it expands the list in place (max 8 rows, scroll).
- Clicking a row focuses that thread. This is the way into hidden subagent threads.
- The rail is hidden when the thread has no parent and no children. In Medium/Narrow, the same content goes in a popover from the header chip.

### 4.7 Laptop framing (`Framing.LAPTOP`)

Opened by the T3 laptop item (another agent owns the item). The entry point is `mod.openPanel(Framing.LAPTOP)` → `new T3Screen(mod, null, Framing.LAPTOP)`.

**Backdrop:** the world, dimmed with `SCRIM`. Call `graphics.blurBeforeThisStratum()` once at the start of render, so the blur applies under the laptop only in this framing. That costs one full-screen blur pass, the same as vanilla menus.

**Screen rect:** 16:10, `sw = min(W − 64, (H − 56) × 1.6)`, `sh = sw / 1.6`, centred horizontally, with top at `(H − sh − 22)/2`. At 960×540 this gives 742 × 464. The panel lays out inside the screen rect using the same breakpoints (that is Medium here), so the rail collapses to the header chip.

**Bezel** (all fills, no textures):

- The lid is the screen rect expanded by 7, `0xFF1B1B1F`, r 6, with a 1 px top highlight `0xFF2E2E34`.
- A 2×2 camera dot `0xFF2A2A30` centred in the top bezel.
- The screen itself is `0xFF0C0C0E`, with the panel inside at 0 margin and r 0.

**Base:** a deck below the lid, 14 tall and 40 wider than the lid, `0xFF26262B` with a 1 px `0xFF34343A` top edge, plus a centred 60×3 notch `0xFF1B1B1F`. A soft 4 px shadow under the base (two fills at `0x40000000` and `0x20000000`) is optional.

**In-screen title bar (h 14):** three 5×5 dots in `0xFF3A3A40` (decorative, not traffic-light colours, to avoid looking like a copy of macOS) and "T3 Code" in `META`/`TEXT_MUTED`, centred.

**Behaviour:**

- Esc / open key closes. Optionally play a click and a "lid close" 120 ms scale-down if the laptop agent provides it.
- `isInGameUi()` stays true. Pausing follows the existing setting.
- In 1920@4 (480×270, Short), the laptop framing falls back to `FULL`, because a framed screen would be unusable.

---

## 5. Interaction details

| Input | Behaviour |
|---|---|
| Enter | Send and close (as today). In a question: answer. |
| Shift+Enter | Send and stay. |
| Esc | Close the picker, popover or narrow sidebar first, then the panel. |
| Y / N (composer empty, approval visible) | Approve / deny the shown approval immediately. |
| 1–9 (composer empty, question visible) | Pick an option. |
| Alt+↑ / Alt+↓ | Previous/next visible sidebar thread (focus switch, draft parked as today). |
| PgUp / PgDn | Scroll the conversation by 80% of the viewport. End (composer empty) jumps to the bottom. |
| Tab (composer empty, Narrow) | Toggle the sidebar overlay. |
| Wheel | Scrolls the region under the cursor: sidebar, snoozed list, card, picker or conversation. |
| Click a tool/thinking row | Toggle expand. The layout invalidates only that block. |
| Click a project header | Collapse/expand the group. |
| Right-click a thread row | Context popover: Pin to desk / Unpin, Open activity, Copy thread link. Immediate, no confirmations. Snooze/settle are only shown if T3's API exposes them on that protocol. |
| Hover a footer icon, a truncated title or the connection dot | Vanilla `setTooltipForNextFrame` tooltip, after a 400 ms delay. |
| New message while scrolled up | A "↓ New messages" pill (`BG_RAISED`, `META`) floats 8 above the composer. Click jumps to the bottom. The scroll anchor logic is kept (pixel version). |

All hover states are computed in render from `mouseX/Y` against rects stored in the **layout** (not recomputed), and hit tests in `mouseClicked` use the same rects.

---

## 6. Class plan (mapping onto the current structure)

New files sit in the `dev.maxwellyoung.t3craft` package for package-private access, or in a `ui` subpackage if preferred.

| New / changed | Responsibility | Replaces in `T3Screen` |
|---|---|---|
| `T3Theme` (new) | Colour tokens, spacing constants, breakpoint constants | Scattered literals (`0xFFE5E7EB`, etc.), `MARGIN/SIDEBAR/ROW/HEADER/...` |
| `T3Fonts` (new) | Font ids per scale, the cached `Style`s (`body/strong/meta/title/mono`), `charWidth`, `epoch` | Implicit vanilla font |
| `T3Draw` (new, static) | `round(g, x0,y0,x1,y1, r, argb)` (stepped corners), `outline`, `dot`, `spinner(g,x,y,color,timeMs)`, `chevron`, `ellipsize(StyledText,w)` (caches by (text, w, epoch)) | Ad-hoc `fill` calls, `T3Hud.ellipsize` in hot paths |
| `T3Button` (new, extends `AbstractButton`) | Flat button: ghost/filled/icon variants, tooltip, key hint. Overrides `extractWidgetRenderState`. | Vanilla `Button.builder(...)` × 8 |
| `PanelLayout` (new record) | All rects (sidebar, header, content, column, rail, footer, laptop bezel) + breakpoint flags, computed in `init()` | `listTop()`, `projectTop()`, `chipX()` and friends evaluated per frame |
| `T3SidebarModel` (new, pure) | `build(snapshot, prefs, envFilter, collapsed, snoozedOpen, focusedId)` → `List<SidebarItem>` (sealed: `ProjectHeader`, `ThreadItem`, `SnoozedHeader`), plus `childrenByParent`. Memoised on `(snapshot identity, filter, collapsed, snoozedOpen, pinsVersion)`. | `sidebarRows()`, `machines()`, `projects()`, called 6+ times per frame (perf item 4) |
| `T3Sidebar` (new) | Draw + hit-test + scroll of the model. Footer buttons. | `extractSidebar`, sidebar branches of `mouseClicked/mouseScrolled` |
| `T3Header` (new) | Breadcrumb, status, model chip anchor, Review, overflow, lineage chip | Title/status part of `extractConversation`, `extractModelChip`, `review`/`stop` buttons |
| `T3Timeline` (new, pure) | `Focus` → `List<Block>` (sealed: `UserMsg`, `AgentMsg`, `ToolGroup`, `Thinking`, `Working`, `ErrorNote`) with stable ids. Built once per new `Focus` instance. | The `lines()` loop |
| `T3Markdown` (changed) | Split in two: `parse(text) → Doc` (cached by `text.hashCode()+length`, an LRU of 256) and `layout(Doc, width, epoch) → List<Line>`. `Line` gains `advance`, `x`, `style role`, `bg` kind. No `withBold`. Uses `T3Fonts` styles. | `render(...)` |
| `T3LayoutCache` (new) | `Map<BlockKey, BlockLayout>` keyed by `(blockId, textLength, textHash, width, epoch, expanded)`. Cumulative tops array. A streaming block re-layouts at most every 250 ms, and only if its text length changed (perf item 3). | `cachedFocus/cachedLines` (which re-rendered *every* message whenever any message changed) |
| `T3Conversation` (new) | Pixel scroll, viewport culling, block drawing, expand toggles, new-messages pill | Conversation part of `extractConversation` |
| `T3ApprovalCard`, `T3QuestionCard` (new) | Draw + hit-test. Keep the existing answer/approval *logic* in `T3Screen` (`currentApproval`, `answerQuestion`, `submittedInputs`, ...), which is unchanged. | Card parts of `extractConversation`, `extractQuestion`, `approve`/`deny` buttons |
| `T3Composer` (new) | Box, EditBox child, chip, send/stop button, placeholder, grow logic. Picker drawing moves here (`extractPicker` → `T3ModelPicker`). | `composer` setup in `init`, `updateHint`, `extractPicker`, `chip*` |
| `T3LineageRail` (new) | The rail card and the popover variant | – |
| `T3Screen` (slimmed) | Coordinator: owns state (focus, drafts, newThread, picker, selection), builds `PanelLayout` in `init`, delegates render/input. Keeps every `*ForTest` hook with the same semantics, so `SelfTest`/`UiChecks` keep passing. Gains `Framing framing`. | – |
| `T3Hud`, `T3DeskScreen`, `T3ReviewScreen`, `T3ActivityScreen`, `T3ConnectionsScreen` | Step 8: adopt `T3Theme` colours and `T3Fonts.body()` for body text, plus `T3Button`. No structural change. | – |
| `T3State.ThreadRow` (changed) | Add `boolean settled, snoozed, Instant snoozedUntil, String parentId, boolean subagent, Instant updatedAt`, parsed once in `row()` | Ad-hoc `raw` reads |

**Per-frame budget rule:** `extractRenderState` may only read cached models and layouts, compute hover states and issue draw calls. No `font.split`, no `Instant.parse`, no stream pipelines over all threads, no `String.format` in loops. Ellipsized titles are cached per item and width.

---

## 7. Migration order (each step shippable and testable on its own)

1. **Tokens and flat chrome (no font change).**
   - Add `T3Theme`, `T3Draw` and `T3Button`.
   - Replace vanilla buttons; recolour.
   - Remove every `withBold`.
   - Line advance 11 with the vanilla font. Add the reading-column width cap.
   - This is already a large readability win and a low-risk PR.
2. **Fonts.**
   - Add the Inter subsets, the JSONs and `OFL.txt`; add `T3Fonts` and the epoch.
   - Switch `T3Markdown` and the sidebar text to styled components.
   - Tune `shift` at scales 2/3/4 on a 1920 and an 854 window.
   - Add the `ui.font` config escape hatch.
3. **Sidebar model.**
   - Extend `ThreadRow`; add `T3SidebarModel` with the visibility rules, project grouping and the snoozed section.
   - Add `UiChecks` cases: subagent hidden but its approval present in `T3Decisions`; settled hidden unless pinned; focused settled thread still shown; snoozed section count.
4. **Timeline and layout cache.**
   - Add `T3Timeline`, the parse/layout split, `T3LayoutCache` and pixel scroll.
   - User bubbles, tool groups, thinking rows, working line, caret outside the text.
   - Check perf with a 500-message thread while streaming (target: < 0.3 ms panel CPU per frame when nothing changes).
5. **Header, composer and cards.** Breadcrumb header, the composer box with chip and send/stop, the approval/question card restyle, and the upward picker.
6. **Responsive and rail.** Breakpoints, narrow overlay, Short mode, `T3LineageRail` and the popover.
7. **Laptop framing.** `Framing.LAPTOP` plus the hook for the laptop item (coordinate with the laptop spec owner on the open call and sounds).
8. **Polish (optional).** JetBrains Mono for code, multi-line `T3TextArea` composer in Inter, theme adoption in the other screens.

---

## 8. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| `FontTexture` is NEAREST-sampled, so a TTF is only crisp when `oversample == guiScale` | Shimmering text if one JSON is used for all scales | Per-scale JSONs chosen at layout time (§2.3). Re-layout on scale change via `epoch`. |
| `Font.lineHeight` is fixed at 9 and Inter's ascender differs | Overlapping or misaligned text if any code still uses `font.lineHeight` | All layout uses `T3Theme` advances. Tune `shift.y` once per size. Grep for `lineHeight` in the new classes as a check. |
| `font.width(String)` and `EditBox` cursor math are unstyled | Wrong widths or cursor offsets | Always measure `Component`s with the Style. The composer stays on the vanilla font until `T3TextArea` (step 8). |
| Glyphs missing from the Inter subset | Missing-glyph boxes | `reference` fallbacks to `minecraft:include/default` and `include/unifont` in every JSON. Keep `·…›⌄▾✓✗↑⇧⚑⇄≡▣✎⌕◇⚙⑂` in the subset; verify the list with `pyftsubset --text`. |
| `withBold` sneaks back in through Markdown | Smeared text | `T3Markdown` maps bold to `T3Fonts.strong()`. Add a `UiChecks` assertion that no `Line` style has `isBold()`. |
| T3 payload fields (`settled`, `snoozed`, `parentThreadId`, `relationshipToParent`) are absent on the legacy websocket shell | Sidebar shows too much, or subagents leak | Fallback heuristics (§4.1). Log once when the fields are missing. Never hide the focused thread. |
| Hiding children upstream would break Decisions | Missed approvals | The filter is only in `T3SidebarModel`, plus a `UiChecks` test (step 3). |
| GUI scale 1, 5 or 6 ("Auto" on 4K or tiny windows) | Soft text | Generate JSONs for 1–6 (cheap), or clamp to 2–4 and accept softness. |
| 854@2 is only 427 GUI px wide | Cramped | The Narrow breakpoint gives the full width to the conversation; the sidebar is an overlay. The column min of 260 still fits. |
| Laptop framing at Short heights | Unusable | Falls back to `FULL` when `H < 320`. |
| Rounded corners via stepped fills | Many fill calls | ≤ 12 extra fills per rounded rect, and fewer than 30 rounded rects on screen. Negligible next to text. |
| Blur in laptop framing | One extra full-screen pass | Only in `LAPTOP`. `FULL` keeps `isInGameUi` transparent with a 95% panel. |
| Licence | – | Inter and JetBrains Mono are OFL 1.1. Ship `OFL.txt` in `assets/t3craft/font/` and credit them in the README. Compatible with MIT. |
| `UiChecks`/`SelfTest` depend on old geometry (`scrollSidebarForTest` uses `MARGIN+5`) | Test breakage | Keep the `*ForTest` methods and route them through `PanelLayout` rects. Update `sidebarCountForTest` to count `ThreadItem`s. |

---

## 9. Acceptance checklist

- At 1920×1080 at scale 2, scale 3 and scale 4, and at 854×480 at scale 2, plus 2560×1440 at scale 2:
  - No text overlaps.
  - The reading column is ≤ 75 chars.
  - The sidebar shows only active + pinned threads, plus the "Snoozed N" row.
- No `withBold` anywhere in UI code. Body text is visibly lighter than vanilla (compare with screenshot #1).
- A subagent's approval appears in Decisions and as a card in its parent's view, while the subagent has no sidebar row.
- With a 500-message thread streaming, the frame time with the panel open is within 0.5 ms of the panel closed.
- Laptop framing is visually distinct, and the panel inside is fully functional.

---
## OVERRIDE (owner decision, 2026-11-05): simplicity / zero setup
This supersedes every Connections reference above (line ~236 footer icon, the ~265 overflow item, the ~449 T3ConnectionsScreen restyle):
- NO Connections icon, menu item or screen for the player. Connection is automatic (see [Decisions](../../DECISIONS.md#2026-11-05--zero-setup-auto-connect-to-the-local-t3)).
- Keep only the connection dot (tooltip = one-sentence status) and the one-line error strip, which carries a single action button ("Open T3 Code" / "Try again").
- Footer icons: Decisions (badge), Review, Activity, Pin. Drop "Pins" management if it isn't essential; prefer pinning from the ⋯ menu.
- Fewer controls overall; every screen has one obvious purpose.
