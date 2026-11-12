> **Status:** implementation spec, 2026-11-05. Pictures and summary: `docs/design/characters-and-laptop.md`. The laptop assets referenced below are in this folder.

> **Current direction:** the [simple native UI](../../design/native-ui.md): the laptop is a plain Minecraft screen and clicking an agent opens a small card beside it. The streamed web UI is opt-in (`webUi: true`). The designs below are earlier explorations.

> **Since removed:** `T3Village`, `ServerVillage` and `VillageLayout` (the free-standing village) were removed in PR #13. The office residents now live in `OfficeResidents` (client) and `ServerOffice` (shared server). Read those names wherever this spec mentions the village classes.

# T3 Craft: player-shaped agents and laptops (26.3 implementation spec)

## Decision

Use vanilla **Mannequin entities in both client-only and server modes**, plus narrowly scoped client animation mixins for T3 agents. They are player-shaped avatars, not logged-in players. Keep OfficeBrain as the movement authority; do not introduce fake connections or mob navigation. Ship provider skins as 64×64 PNGs and use the 26.3 profile texture/model fields.

For the core distribution, use a **component-marked vanilla paper item as the laptop**, with a custom item model, and **ItemDisplay + Interaction entities on desks**. This preserves the existing ability to join vanilla servers and lets vanilla clients join the shared office. A native custom item/block is specified below as a separate optional content add-on; do not register it in the compatibility-focused core jar.

The unavoidable boundary: a client mod cannot give the player a real inventory item or place a real block on an arbitrary unmodified survival server. Auto-give is authoritative in T3 worlds (integrated or dedicated server running T3). Elsewhere, the laptop is available automatically through a client HUD button/keybind, and an ordinary held paper can be locally rendered/used as a laptop. Do not create ghost inventory stacks or require creative-mode packets.

## Evidence and verification

Repository baseline: `56595ad7961912a309213f198359348db0c4e860`, fetched `origin/main`. The nine requested files are preserved under `repo-evidence`; all shared-repository reads used `git show origin/main:<path>`. The shared working tree was not edited. The independent clone is `src`.

`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew genSources` completed successfully in the clone. Exact versions: Minecraft `26.3`, Fabric API `0.161.0+26.3`, loader `0.19.5`, Loom resolved `1.17.21`, Java 25. Decompiled source is under `minecraft`. These are the actual 26.3 classes, including Fabric's documented access widening; an accessible method marked “access widened” should not be assumed publicly callable in an unmodified vanilla binary.

The local `specProbe` task compiles the Mannequin factory/component calls, parses the draft cuboid model with Minecraft's `CuboidModel.fromStream`, parses the 3×3 component-bearing recipe with `ShapedRecipe.MAP_CODEC`, and parses a texture-only/slim profile with `ResolvableProfile.CODEC`. All checks passed. See `spec-probe.log` and `SpecProbe.java`. This is source/API and data-format validation, not an in-game rendering or multiplayer test. Performance numbers below are budgets to measure, not benchmarks.

Primary source index (all paths relative to this document):

- `Mannequin`: superclass, profile, supported poses, save keys, private setters, component application.
- `Avatar`: player dimensions, model layers, main hand, no Mob superclass.
- `EntityTypes`: `MANNEQUIN`, `ITEM_DISPLAY`, `INTERACTION`; Mannequin tracking range 32 and update interval 2.
- `ClientMannequin`: client constructor override, profile updates, asynchronous skin cache, description beneath name.
- `ResolvableProfile`, `PlayerSkin`, `PlayerModelType`, `ClientAsset`: precise skin codecs and texture paths.
- `PlayerSkinRenderCache`, `SkinManager`: caching, asynchronous profile/texture work and default skin fallback.
- `RemotePlayer`, `AbstractClientPlayer`: constructor, noPhysics, skin dependency on PlayerInfo.
- `EntityRenderDispatcher`, `AvatarRenderer`, `PlayerModel`, `HumanoidModel`: avatar renderer selection, skin/model extraction and passenger limb pose.
- `Entity`: `setComponent`, `startRiding`, `interact(Player, InteractionHand, Vec3)`, positioning, tags.
- `Display`, `Interaction`: desk rendering and hitbox setters.
- `Item`, `Items`, `BlockItem`, `Blocks`, `BlockBehaviour`: registration and use signatures.
- `CuboidModel`, `CuboidModelElement`, `ItemModels`: JSON models. `BlockModel` is now an interface; do not copy old `BlockModel.fromStream` examples.
- `ShapedRecipe`, `Ingredient`, `ItemStackTemplate`: recipe strings and component-bearing result.

Fabric callback signatures were also inspected with Java 25 `javap` on the resolved dependency jars: `UseItemCallback.interact(Player, Level, InteractionHand)`, `UseEntityCallback.interact(Player, Level, InteractionHand, Entity, EntityHitResult)`, `UseBlockCallback.interact(Player, Level, InteractionHand, BlockHitResult)`, all returning `InteractionResult`; `ServerPlayConnectionEvents.Join.onPlayReady(ServerGamePacketListenerImpl, PacketSender, MinecraftServer)`; `ClientPlayNetworking.canSend(...)` and `ServerPlayNetworking.canSend(ServerPlayer, ...)`.

## Existing code to preserve and change

`T3Village` uses negative entity IDs starting at -7,300,000; it consumes use clicks at START_CLIENT_TICK and moves office avatars at END_CLIENT_TICK for interpolation. Shared entities are matched through deterministic UUIDs. `ServerVillage` and `ServerOffice` create real, invulnerable villagers and remove stale tagged copies when loaded. `OfficeBrain` already owns grid paths, work/wait/lounge destinations, fridge pauses and desk allocation. `VillageLayout` provides labels and project-based villager professions; its free-standing village is capped at eight. `T3Office.WORK` has eight desk seats per floor. Thus 32 agents means four floors, not silently putting 32 into eight seats.

`T3CraftClient.openPanel()` opens connections when unpaired, otherwise calls `minecraft.gui.setScreen(new T3Screen(this))`. `T3Screen` is final and currently positions widgets and hitboxes using global MARGIN/width/height. Add a framing mode to this screen; a subclass or visual-only scale would break input alignment.

`fabric.mod.json` has only client and dedicated-server entrypoints. A new common `main` entrypoint is necessary for integrated-server auto-give/lifecycle logic. It must not initialize a second T3 connection or spawn a second office in singleplayer. Keep client-owned office generation as the default integrated mode until a shared-office capability explicitly takes ownership.

## Feature 1: option assessment

### A. Vanilla Mannequin — recommended

`Mannequin extends Avatar extends LivingEntity`, not `Mob` and not `Player`. It supplies the player-shaped body without player-list, authentication, tab-list, hunger or fake-network-session machinery. `EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND)` goes through `Mannequin.create`; in the client process `ClientMannequin.registerOverrides` installs the client factory. **Do not directly call `new Mannequin(...)` on ClientLevel**: renderer dispatch specifically recognizes `ClientMannequin`, which implements `ClientAvatarEntity`.

Persisted fields are exactly `profile`, `hidden_layers`, `main_hand`, `pose`, `immovable`, `description`, `hide_description`. Profile, immovable and effective optional description have synced entity data. Pose is inherited synced data. Hidden layers are the inverse of the visible player customization mask. Default description is the translated Mannequin label. Valid serialized poses are exactly STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING. There is no SIT pose and no per-limb pose API like an armor stand.

`setProfile`, `setImmovable`, `setDescription`, `setHideDescription` are private. Profile has a public, verified route:

```java
Mannequin avatar = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
if (avatar == null) return;
avatar.setComponent(DataComponents.PROFILE, profile); // Entity public setter -> Mannequin component hook
avatar.setNoGravity(true);
avatar.setSilent(true);
avatar.setPermanentlyInvulnerable(true);
avatar.setCustomNameVisible(true);
avatar.setCustomName(title);
```

Add common `MannequinAccess` Mixin invokers for `setImmovable(boolean)`, `setDescription(Component)` and `setHideDescription(boolean)`. Use `immovable=true` plus noGravity; OfficeBrain explicitly supplies positions. Do not call villager-only `setNoAi`, `setPersistenceRequired`, `setVillagerData` or `setUnhappyCounter`. Direct `setPos` remains available regardless of immovable. Scope client noPhysics to local synthetic avatars if collision pushes cause drift. Server movement remains authoritative; clients must not run a second OfficeBrain against server avatars.

Name: set custom name to truncated thread title; set description to colored status with elapsed time or waiting summary. Set hide_description=false for T3 agents; set true only when the name/bubble layer fully replaces it. `ClientMannequin.belowNameDisplay()` returns this description. Keep the existing 32-character title and 36-character waiting detail limits. Never put prompts, credentials or connection URLs into publicly synced names. In shared worlds status text is visible to other players, as the existing labels already are.

Walking/look-at: reuse `T3Office.path` and speed 0.09 blocks/tick. This is deterministic waypoint motion, not a vanilla PathNavigation API (Mannequin has none). Apply setPos after local ticking; copy body/head/yaw through `setYRot`, `setYBodyRot`, `setYHeadRot`. Update client `walkAnimation.update(...)` from measured movement, including a decay to zero when stopped. For shared avatars let server movement packets drive movement; add measured-distance animation extraction on the T3 client if vanilla LivingEntity animation does not follow manual positioning adequately. Test this explicitly. Keep head look independent of seated body yaw, clamp to ±50°, select nearest viewer at most every four ticks, and ease rotation. No head snap every rendered frame.

Interaction: retain local ID → thread mapping and shared deterministic UUID matching. Generalize the current Villager check to Mannequin. Centralize selection in `AgentInteractionRouter`; `focus(threadId)` then `openPanel(LAPTOP)` opens only a locally known/authorized thread. Entity UUID is identity, not authorization. Unknown or unavailable thread opens a neutral “not available on this connection” view; never dispatch to the current focused thread by accident. Consume both use and attack against negative-ID entities so no entity interaction packets are emitted for IDs absent on the server. A START tick key drain alone must be tested for held-use repeat; a targeted client mixin cancellation at `Minecraft.startUseItem()` and `Minecraft.startAttack()` is the robust backstop (both private methods verified in the 26.3 source). Shared positive-ID entities may use normal server interaction but opening the panel remains client-local.

Vanilla clients on a T3 server recognize Mannequins and render name, description, profile skin, base pose, movement and equipment. They cannot open T3 screens or run the custom typing/raised-hand animations. Vanilla-client visibility here is a conclusion from the registered vanilla entity, synced fields and vanilla ClientMannequin/AvatarRenderer classes, not a claim of a completed interoperability test.

### B. Client-only RemotePlayer / AbstractClientPlayer

`new RemotePlayer(ClientLevel, GameProfile)` is real 26.3 API; the constructor sets `noPhysics=true`. Add it to ClientLevel with a negative ID and local thread map. It gets player avatar rendering and the same render-time animation possibilities. It is client-only; other players and a vanilla server never see it.

A GameProfile passed to the constructor does **not** by itself make its custom skin display: `AbstractClientPlayer.getSkin()` uses connection PlayerInfo and otherwise returns `DefaultPlayerSkin.get(UUID)`. A local subclass must override `getSkin()` to supply the provider `PlayerSkin`, or resolve a configured Mojang profile asynchronously. Do not inject fake player-list entries merely to load a skin. Override display-name behavior deliberately since Player names are profile-oriented. Use a client below-name/label layer for title + status.

For desks, sitting, walking, raised hand, bubbles and interaction, the same client animation and input interception are needed as option A. No mob pathfinder exists. It does not solve the dedicated-server representation: a ServerPlayer fake requires connection, player-list and spawn lifecycle work; a RemotePlayer class cannot be instantiated on a dedicated server. Running two different avatar implementations buys little here. Cost is still small at 8–32, but player ticking and special rendering behavior increase maintenance compared with Mannequin.

### C. Custom entity + player renderer

A registered custom living entity can use `PlayerModel`, but `AvatarRenderer<T>` requires `T extends Avatar & ClientAvatarEntity`. Either extend Avatar with a separate client-capable implementation or write a LivingEntityRenderer with suitable AvatarRenderState and PlayerModel. A plain Mob cannot be dropped into AvatarRenderer. Build wide/slim model instances and choose by provider skin. Synced fields would cover provider, activity, seated flag, phase start and label; pathing is still simplest through OfficeBrain. A Mob-based alternative could have navigation, at the cost of losing the direct AvatarRenderer type contract.

It can support all requested animation and right-click behavior cleanly, but custom entity types require matching mod/registry support on the receiving client. A client-only custom registration is also unnecessary compatibility risk. Sending it to vanilla clients is not equivalent to sending a vanilla Mannequin; supporting them would require a separate vanilla disguise/packet representation. Do not add that infrastructure for this feature. Performance is not the deciding factor; network and renderer integration are.

## Provider skins, common to all options

Use a `ProviderAppearance` record containing stable provider key, texture identifier, model type and accent. Suggested defaults: Claude terracotta/cream wide; Codex graphite/mint wide; Muse violet/sky slim; unknown provider slate/white wide. These are design choices, not assumptions about provider API IDs. Resolve provider through the row's modelSelection instanceId and existing provider catalog; do not hash thread titles or infer identity from display names. Cache the chosen appearance on the agent; appearance updates only when provider selection changes.

Ship `assets/t3craft/textures/entity/agent/{claude,codex,muse,default}.png`, 64×64 with normal player UV layout and optional transparent second layers. Both slim and wide use that layout, but arm pixels must be authored for 3- vs 4-pixel arms. In 26.3 the type is `PlayerModelType.WIDE`/`SLIM`; JSON values are `wide`/`slim`. Mojang services use legacy `default`/`slim`, which is a different naming layer.

For Mannequin, decode through the codec (or central helper wrapping it):

```json
{"texture":"t3craft:entity/agent/claude","model":"wide"}
```

`ResolvableProfile.CODEC` includes `PlayerSkin.Patch.MAP_CODEC`, whose keys are texture/cape/elytra/model. ResourceTexture expands the identifier to `textures/entity/agent/claude.png`; do not include `textures/` or `.png` in the JSON identifier. A texture-only profile is static, so it does not require a live Mojang account. The cache still asynchronously produces the default skin plus the texture patch. For RemotePlayer's overridden getSkin use `new PlayerSkin(new ClientAsset.ResourceTexture(id), null, null, PlayerModelType.WIDE, false)`.

Optional user skin: `ResolvableProfile.createUnresolved(UUID)` (prefer UUID to renameable account name) or `createUnresolved(String)`, then public setComponent. Cache per configured profile, never resolve per agent tick. `createResolved(GameProfile)` is for already obtained profile data, including genuine texture properties. Do not invent signed properties or assume arbitrary image URLs are Mojang skin profiles. Keep provider fallback visible during resolution/failure on the T3 client. No user lookup is needed for bundled defaults.

Server sends profile identifiers, not PNG bytes. Modded clients have bundled assets. Vanilla clients need an offered server resource pack containing the same asset paths; declining the pack means branded resource skins are unavailable. If supporting pack-decliners visually is a requirement, choose a real Mojang profile skin as the server baseline and let T3 clients override it to the bundled provider appearance. That fallback cannot guarantee branded skins on offline clients. Resource pack distribution is an explicit server setup/deployment concern; do not claim the mod jar transfers its assets automatically.

## Status animation contract

Add to OfficeBrain.Agent: providerKey, `Activity { WALK, TYPE, WAIT, RELAX, IDLE, OFFLINE }`, activityStartTick, seated, bodyYaw and headYaw. Do not transmit continuous sine-wave arm angles. Activity is derived from zone + settled + connectivity; walking wins until the path finishes, and offline disables typing/sounds. Existing ERROR should have a visible red badge; retaining its current lounge destination is fine unless product behavior changes separately.

- WORKING settled at work: seated appearance, upper arms forward about -65°, elbows implied by whole-arm tilt, alternate arm x rotations by ±3° at roughly 2.5 Hz, hands above keyboard. Phase offset from stable thread hash avoids synchronized typing. Brief head-down angle 10°. Minecraft player arms have no separate elbow bones.
- NEEDS_YOU: at whiteboard, stand; right arm raised about -145° with 15° outward spread, held steadily. Small amber `?` bubble over shoulder, optionally cycling to `…` for non-question waits; do not show raw approval text. Vanilla fallback is amber description + existing note particles.
- DONE settled at sofa: seated legs, arms down, slight head movement and existing honey-bottle drink only when appropriate. Other lounge destinations remain standing. No forced sleeping pose; SLEEPING rotates the body rather than creating a chair sit.
- WALK: vanilla limb movement; blend out seated/gesture animation over six ticks before moving. IDLE: neutral. OFFLINE: gray status and no work animation.

Implementation: a client mixin adds a small T3 animation snapshot to AvatarRenderState. At the tail of `AvatarRenderer.extractRenderState`, always reset it, then populate it **only for a known T3 ClientMannequin UUID/local ID**. At tail of `PlayerModel.setupAnim(AvatarRenderState)`, apply the snapshot after vanilla animation resets. Child sleeve/pants layers follow their parent parts in the 26.3 model; verify wide/slim and held-item alignment. Never mutate a shared model during entity ticking. Rendering may be deferred, so the snapshot must be self-contained, not a mutable Agent reference. Use `HumanoidRenderState.isPassenger=true` in extraction for visual seated legs, with an explicit render translation/seat calibration; do not change the entity's actual Pose for the sitting animation. Keep selection hitboxes and name anchor aligned with the visible torso.

Sitting alternatives and limitations:

1. **Recommended client visual seat:** no extra entity; logical avatar stays at the chair anchor, model gets seated legs and calibrated translation. Existing sink values (work +0.3, sofa +0.45) are not a genuine seat implementation and must not be copied blindly. Author seat anchors with desired hip height ~0.6 blocks above floor; verify feet/keyboard fit in-game. Vanilla clients see a standing/crouched fallback at that anchor, not the full seated animation.
2. **Actual mount:** `Entity.startRiding(seat, true, false)` and `stopRiding()`; vanilla passenger rendering bends legs. Use a vanilla invisible, invulnerable, no-gravity armor stand as a prototype carrier if vanilla clients must see seated legs. Passenger attachment height and bounding boxes require calibration; an invisible entity is not automatically non-collidable. Avoid Marker: it has tracking range 0 and is not an appropriate client-visible seat transport. Custom seat entity types sacrifice vanilla compatibility. Seats must be removed/unmounted on every status transition, unload, disconnect and office teardown. Adds up to one entity per seated agent.
3. **Crouch/sink hack:** valid CROUCHING pose but bent standing legs, shorter bounding box and possibly head clipping. Accept only as a vanilla fallback, not the promised T3-client visual.

The first release promises full seated/typing/raised-hand visuals to T3 clients and readable vanilla fallbacks. Genuine vanilla seated legs can be a later, tested mount option. This distinction is necessary because stock Mannequin metadata cannot encode those arm poses.

## Character class and migration plan

- `AgentAppearance`: common provider/profile/label policy; no client imports.
- `AgentEntityFactory`: spawn/create profile/name/description, deterministic UUID, common flags. Use invokers only for the three private description/immovable methods.
- `AgentIdentity`: preserve existing UUID bytes (`t3craft:` and `t3craft-office:`) while renaming villagerUuid helpers; changing types does not require changing identity. Add separate namespace UUIDs for desk displays/hitboxes.
- `T3Village`: Map<String, Mannequin>, factory via EntityTypes, negative-ID lifecycle, removal on level change, no villager profession calls, shared Mannequin matching. Suppress local duplicate avatars only for a confirmed shared office; a capability packet alone should not hide unrelated local offices.
- `ServerVillage`: same replacement, invulnerability and tag cleanup; MAX_AGENTS remains 8 unless explicit configuration raises it.
- `ServerOffice`: same replacement; keep brain/roster and server movement. Remove `kill @e[type=villager,...]` as the only cleanup path. On migration remove both legacy tagged Villagers and new tagged Mannequins, with office vs village tags kept distinct. Recreate on a subsequent tick to avoid UUID collisions; do not delete arbitrary vanilla entities with merely matching display names.
- `OfficeBrain`: pure data changes above, no client renderer dependencies. Add desk/laptop binding based on owner+floor+stable desk, not list position. Do not derive gestures from custom-name parsing.
- `T3AgentRenderHooks`: client-only mixins for extracted state/pose; common mixin config only for Mannequin access. Entity scoreboard tags are not a replacement for client identity synchronization; use UUID maps or a negotiated metadata payload.
- Shared rich animations: a capability-gated S2C agent-view payload supplies entity UUID, provider/activity, seat anchor and phase start. Send on tracking/appearance/activity changes and reconnect. No private credentials; only data already authorized for the viewing client. Paired client may derive activity from its own thread state when no payload exists. An unknown vanilla Mannequin must never acquire a T3 pose by class alone.

## Performance and lifecycle budgets

At 8–32 agents: one avatar each, up to one display and one interaction per desk (24–96 total entities), plus seats only if that option is enabled. Use no mob Brain/pathfinder, no per-frame network/profile work, one path recomputation per destination change. Status/appearance sync remains every ten ticks and sends only changed metadata; server type update interval is already two ticks. Profile render cache expires after five minutes of inactivity; share provider profiles rather than inventing one profile per frame.

Reduce particles to one small status cue per agent per second near the viewer, cap total typing sounds to two per second per office, skip animation detail outside 32 blocks and hide labels outside the chosen readability radius. Keep normal frustum/distance culling. Cache UUID→thread, desk→thread and snapshot row maps rather than repeatedly scanning every row for every avatar. Keep entity operations on the game thread. Benchmark 8/16/32 visible agents, all working vs all walking, recording frame-time and server tick deltas against the empty office; acceptance target <2 ms additional server tick time and no recurring >5 ms client frame spike on the target machine, to be measured, not asserted.

## Feature 2: core laptop item and desk objects

### Portable laptop

No custom registry entry is needed in core. `LaptopStacks.create()` returns:

```java
ItemStack stack = new ItemStack(Items.PAPER);
stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("t3craft", "laptop"));
stack.set(DataComponents.CUSTOM_NAME, Component.literal("T3 Laptop"));
CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt("t3craft_laptop", 1));
```

Recognize item type + custom_data marker; do not treat a renamed paper as authoritative. Component is a UI affordance, not a secret or permission token. Keep count 1 for granted stacks. Right-click air, block or entity while holding a marked laptop opens the local panel with laptop framing, consumes the use, does not consume the stack, and does not open the underlying container. Central interaction priority: targeted desk laptop → targeted agent → held laptop → ordinary block/entity use. Add sneak bypass for ordinary world interactions. Trigger once per press with debounce; preserve offhand behavior. Use the verified Fabric callbacks or the unified input cancellation path, returning PASS for everything else. Avoid opening twice from both tick and callback.

Assets:

- `assets/t3craft/items/laptop.json` ← [laptop.item.json](laptop.item.json): `{"model":{"type":"minecraft:model","model":"t3craft:item/laptop"}}`.
- `assets/t3craft/models/item/laptop.json` ← [laptop.model.json](laptop.model.json).
- `assets/t3craft/textures/item/laptop.png` ← [laptop.png](laptop.png), 32×32 RGBA nearest-neighbor atlas.
- `data/t3craft/recipe/laptop.json` ← [laptop.recipe.json](laptop.recipe.json); directory is singular `recipe`.

Model: six cuboids, base from [2,0,4] to [14,1,13], screen lid from [2,1,3.5] to [14,9,4.5], screen inset, keyboard inset, trackpad and mint indicator. Lid and screen rotate -22.5° about X around [8,1,4]. Front faces south (+Z), user sits on that side. UV coordinates remain 0–16 even though texture is 32×32. No translucent glass, dynamic browser texture, custom shader or per-desk block entity renderer. Palette: charcoal #181D26, slate #5C6877, silver #C5CCD3, mint #59D6AD, screen #111721 with cyan/mint code rows. Small geometry gaps prevent coplanar flicker. Included transforms cover GUI, ground, fixed, and both hands. These are draft art assets pending an in-game fit review.

Recipe uses three glass panes across top, iron/redstone/iron in middle, three iron ingots at bottom. Output is one marked paper with item_model/custom_name/custom_data components. Ingredients in 26.3 are item-ID strings (`"minecraft:iron_ingot"`), not the old `{ "item": ... }` shape. Core recipe can be used by vanilla clients on a T3 server because both ingredients and output are vanilla item types; the custom item model needs the pack. Grant recipe discovery with the first laptop if desired; no manual crafting is required for onboarding.

### Desk laptops

Add pure `DeskLayout.laptop(floor, desk)` derived from existing WORK: chair.x==5 → table x=6, chair.x==8 → table x=7, same z as chair; table is a top oak slab at floor origin Y. Laptop rests at `originY + floor*FLOOR_HEIGHT + 1`. West-side chairs need screen front facing west, east-side chairs front facing east. Empty desks still have a laptop, with neutral screen and no thread binding.

Create `Display.ItemDisplay` via `EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND)`. Public setters in this version: `setItemStack`, `setItemTransform(ItemDisplayContext.NONE)`, `setTransformation(Transformation)`, `setBillboardConstraints(Display.BillboardConstraints.FIXED)`, `setViewRange`. The baked model is centered around its block origin for display; account for the -0.5 local centering when setting the entity position (start with tabletopY+0.5 for model base Y=0), then verify in-game. Rotate around Y so +Z front faces the chair. No per-tick screen texture changes; a status-indicator model variant may be selected on state transitions.

ItemDisplay alone is not the clickable desk object. Pair it with `Interaction` via `EntityTypes.INTERACTION.create(...)`, `setWidth(0.8F)`, `setHeight(0.65F)`, `setResponse(false)`, centered on the laptop footprint at tabletop height. The last three setters are public in the Fabric widened source. Use separate IDs/UUIDs, owned by `DeskLaptopManager`, and maintain hitbox→desk→thread mapping. The footprint is deliberately forgiving and must not block the seated avatar's clickable torso. For a client-only office both entities use negative IDs; intercept use/attack as above. Shared desks use real entities. Validate distance, dimension and current binding if a server payload is requested; do not put a T3 command into an item name or execute commands on arbitrary entity click.

Click resolves the current occupant by stable roster preference, focuses that thread and opens LAPTOP framing. Empty desk opens the general panel/new-thread flow; unavailable occupant opens an explicit unavailable message. Reassignment while screen open never silently retargets a pending prompt. Rebuild bindings on roster changes; display and hitbox survive an agent walking to the whiteboard/sofa. Cleanup covers office move/remove, unload, reconnect, floor-count change, saved entity reload and stale ownership. No duplicate client desk objects when the same server office is authoritative.

### Laptop framing

Add `PanelPresentation { OVERLAY, LAPTOP }`, `openPanel(PanelPresentation)` and an optional initial thread argument without bypassing `focus`. Existing keybind keeps OVERLAY; laptop interactions use LAPTOP. Keep unpaired connection routing, pending approvals, draft preservation and `isPauseScreen()==false` behavior.

Refactor T3Screen to a shared `PanelBounds` calculated in init/resize. LAPTOP uses a centered dark bezel (8–12 GUI pixels), a 10-pixel hinge and shallow 20-pixel keyboard lip below the content. Use the same mint indicator as the model. All widgets, scroll regions, model picker and input hit tests use the same content bounds; do not merely scale the rendered screen. If viewport is too small for sidebar+conversation, reduce framing to 4 pixels and use the existing functional layout. No decorative bezel may cover approval controls. Esc returns to play; opening animation is optional, ≤120 ms, with reduced-motion opt-out. Do not simulate a browser/OS or add a second session/auth layer.

### Auto-give and server capability

Add common initializer `T3CraftCommon implements ModInitializer` through `main`, registering common events and optional capability payload types before client/server entrypoints. Register `ServerPlayConnectionEvents.JOIN`; enqueue give on the server thread after player inventory is ready. A T3 world is explicitly one created by the T3 launcher or one where a T3 office/world-mode flag was enabled. Merely installing the client mod must not grant items in every unrelated singleplayer save.

Persist a per-world set of player UUIDs with grant version (e.g. `laptopGrantVersion=1`) using a world-scoped saved record; a config file keyed only by username is insufficient. On eligible join: first inspect inventory for a marked laptop, then attempt `player.getInventory().add(stack)`. Mark the grant completed only after successful insertion or an existing token is found. If full, retain pending grant and retry on a bounded server tick schedule; do not drop infinite laptops at spawn. Reconnect and respawn must not grant duplicates. A client HUD laptop button remains available if the item is later lost. This satisfies first-join automatic access without requiring creative commands or enabling cheats. Native add-on mode substitutes its own item factory.

Capability handshake advertises office/laptop support and protocol version, not secrets. Use `canSend` before custom payloads and maintain per-connection state. Never send an unknown payload to an unmodified server, never infer mod presence from server brand text, and clear capabilities at disconnect.

On a vanilla server: automatically expose the HUD laptop shortcut and rebindable laptop key. For the held-object experience, use existing `Items.PAPER` as a local visual carrier while laptop mode is enabled. Pass a **copy** with ITEM_MODEL overridden to the item render resolver; do not mutate network-owned inventory stacks or overwrite every paper resource globally. Intercept paper use locally only when this feature is enabled; default ordinary paper behavior is otherwise unchanged. Components stored by the server cannot be changed persistently by a client-only mod. Desk ItemDisplay/Interaction objects can still be locally created at a configured client office; building a real office on that server remains subject to existing permissions and is not solved by this feature.

Vanilla client on T3 server: sees Mannequins and real ItemDisplay/Interaction desks. With the resource pack it sees branded skins and laptops; without the pack those custom models/textures are unavailable. It cannot open T3, and a click should be harmless. Offer a concise ordinary message that the T3 client mod enables interaction, with rate limiting. Do not force external application actions from a vanilla click.

## Native custom item/block alternative (optional content add-on)

This is useful for creative inventory discovery, real placement/mining and native block outlines. It requires common registration on server and clients. Keep it as a separate `t3craft-content` artifact so installing the client core still works on unmodified servers; do not attempt late registration after receiving a handshake. A server sending custom block/item registry content to vanilla clients is outside the core compatibility contract.

Register under `t3craft:laptop` in BLOCK and ITEM registries, with the content add-on as owner. Exact 26.3 construction pattern:

```java
Identifier id = Identifier.fromNamespaceAndPath("t3craft", "laptop");
ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
Block laptop = Registry.register(BuiltInRegistries.BLOCK, blockKey,
    new LaptopBlock(BlockBehaviour.Properties.of().setId(blockKey).strength(1.0F).noOcclusion()));
ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
Item laptopItem = Registry.register(BuiltInRegistries.ITEM, itemKey,
    new LaptopBlockItem(laptop, new Item.Properties().setId(itemKey).stacksTo(1)));
```

`LaptopBlock extends HorizontalDirectionalBlock` (or Block with HORIZONTAL_FACING), initializes its default FACING, defines state properties and placement orientation using the opposite of placer direction, with rotation/mirror support. Use a static shape union matching base/lid, rotated for four directions; no ticking BlockEntity is needed because desk ownership comes from server office mapping. Desk laptops can be replaced at their known block positions after construction. Mining drops the portable item; office generation may choose protected desk instances but must not grant protection by arbitrary display name.

`LaptopBlockItem extends BlockItem`: override `use(Level, Player, InteractionHand)` for air opening; override `useOn(UseOnContext)` so normal use opens and sneak-use delegates to `super.useOn` for placement. A server-side handler consumes use; client-only panel creation goes through a client callback, never an imported Minecraft class in the item. Opening must also work when aiming at containers, so the held-laptop interaction router handles that priority. Override block `useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)` and `useItemOn(ItemStack, BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)` to open its assigned thread, with a safe local/general fallback for a hand-placed unassigned laptop. Both return InteractionResult.

Assets reuse the draft model as `models/block/laptop.json`, with item definition pointing to `t3craft:block/laptop`; blockstates define the four facing rotations, loot table drops the registered item, language adds item/block names. Recipe is identical except result `{ "id":"t3craft:laptop", "count":1 }`. Auto-give calls the shared grant service with this item. A normal custom item without a block is the smaller alternative: register `LaptopItem extends Item` with the same use signature, and retain display-based desk laptops.

When the server lacks the add-on, the core fallback still works, but native items/blocks cannot be placed, crafted or granted there. Do not try to send their stacks or simulate an authoritative placement. Supporting vanilla servers with an installed content add-on needs a dedicated registry-compatibility test; the supported simple distribution is core-only on those servers.

## Commit-by-commit implementation

1. **Extract agent identity and appearance policy.** Preserve UUID strings; add provider mapping and activity DTO; rename villager-specific constants/helpers without changing layout. Extend plain-JVM OfficeChecks for stable desk ownership, provider fallback, activity transitions and disconnected state. No renderer changes yet.
2. **Replace villager entities with Mannequins.** Factory via EntityTypes; profile setComponent; common accessor mixins; name/description; remove villager-only API calls. Update T3Village, ServerVillage, ServerOffice and stale-entity migration. Build and test standalone dedicated-server startup to catch client class loading; manually test one local and one shared avatar, click, reload and removal.
3. **Add provider skin assets and rich client poses.** Four 64×64 skins, wide/slim selection, render-state snapshot mixins, typing/wait/lounge motion, offline suppression and bounded sounds. Validate ordinary players/Mannequins remain unaffected, sleeves and held bottle follow hands, seats align, animation resets after walk and resource reload. Keep vanilla fallbacks documented.
4. **Unify input routing and shared metadata.** Agent/desk/held item priority, press debounce, negative-ID attack/use cancellation, UUID indexes, negotiated per-client metadata/capability messages. Test unknown UUID, stale focus, offhand, held click and reconnect. Do not authorize T3 access based on entity metadata.
5. **Implement portable laptop and framed panel.** Install draft assets/recipe, component stack helper, callbacks, common initializer, bounds-based T3Screen framing. Verify all screen buttons, scrolling, approvals and drafts at common GUI scales; unpaired opens connections. Native content registration is deliberately absent from core.
6. **Place desk laptops and bind roster.** DeskLayout positions/orientation, ItemDisplay+Interaction pairs for local and shared offices, stable desk binding even when agent is elsewhere, teardown and load migration. Test all eight positions across four floors, free desks, pin/unpin, owner/floor removal and click obstruction.
7. **Auto-give and vanilla-server fallback.** World eligibility/grant persistence, full-inventory retry, integrated-server event coverage, HUD shortcut, render-only paper copy. Verify no packets with custom entity IDs/registry items reach vanilla server; no ghost stacks, duplicate grants or dual office ownership.
8. **Ship compatibility assets and validate release.** Generate server resource pack from the same asset paths and versioned hash. Test vanilla client with accepted/declined pack; T3 client with vanilla server; T3 client with T3 dedicated server; integrated singleplayer. Benchmark 8/16/32 agents. Update docs/launcher packaging. Add native content add-on in a separate subsequent commit/release only if real placeable blocks are desired.

## Acceptance checklist and unresolved runtime checks

- Agent looks like a player on a fresh offline T3 client without Mojang lookup; correct arms for each skin. Real player/tab list unchanged.
- Status moves agent to correct stable desk/whiteboard/lounge; full T3-client gestures and readable vanilla fallbacks; no “sit pose” claim.
- Right-click agent or any desk's laptop opens the correct thread; walking away from desk does not change its binding. Unknown thread cannot redirect a send.
- Backtick remains usable. Laptop frame has working input and approvals at small/large GUI scales. First eligible join requires no command, item setup or crafting.
- Full inventory, reconnect, death, office deletion, chunk unload/reload and server restart produce neither duplicates nor orphan hitboxes.
- Dedicated server loads no client renderer class. Vanilla-server mode never grants ghost inventory or writes fake blocks. Vanilla-client mode needs only vanilla entity/item registry types in core.
- Runtime verification still required: mannequin manual-motion walk animation, exact model/table and seated-hand fit, hitbox overlap, resource-pack decline appearance, held-use packet suppression and registry interoperability of any optional content add-on. These are explicitly not established by source decompilation/model parsing.
