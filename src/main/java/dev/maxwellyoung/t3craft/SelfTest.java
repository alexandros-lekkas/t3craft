package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.decoration.Mannequin;

/**
 * Dev-only end-to-end check, enabled with {@code -Dt3craft.selftest=<prompt>}. The prompt
 * {@code look} only opens the panel, captures it, and hands the game back (read-only).
 * Any other prompt: opens the panel,
 * sends the prompt, approves the first request, and captures Minecraft's own framebuffer at
 * each stage into run/screenshots. {@code officeflow:<prompt>} runs the same prompt from the office
 * (whiteboard detail, approve by right-clicking the agent, report book) and {@code sharedoffice:<link>}
 * pairs the dev server and builds its shared office. Never active in normal play.
 */
final class SelfTest {
	private enum Step { HIDDEN_DECISION, HIDDEN_CARD, HIDDEN_SETTLED, STABLE_SITE, STABLE_CHURN, STABLE_CAPTURE, STABLE_OFFLINE, STABLE_RECOVER, STABLE_RESTORE, STABLE_ARCHIVE, SHARED_STABLE, SHARED_CHURN, SHARED_TARGET, SHARED_OPEN, SHARED_UNPIN, AUTH_ERROR, AUTH_RECOVER, PROTOCOL_ERROR, PROTOCOL_RECOVER, RELEASE_SITE, RELEASE_OPEN, RELEASE_CHECK, RELEASE_FEEDBACK, DESK_SITE, DESK_OPEN, DESK_REQUEST, DESK_ANSWERED, DESK_REVIEW_OPEN, DESK_REVIEW, DESK_DIFF, DESK_FEEDBACK, DESK_SIDEBAR, SILK_WORLD, OFFICE_SITE, OFFICE, FLOW_SEND, FLOW_WAIT, FLOW_CLICK, FLOW_DONE, OFFICE_SHARED_PAIR, OFFICE_SHARED_BUILD, OFFICE_SHARED_CHECK, THREADS, PREP, DONE_PANEL, WAIT_WORLD, OPEN, SEND, ASK_WAIT, ASK_ANSWERED, LOOK, WATCH, WAIT_WORKING, WAIT_APPROVAL_OR_DONE, WAIT_DONE, CLOSE, HUD, FINISH, DONE }

	private final T3CraftClient mod;
	private final String prompt;
	private Step step;
	private int ticks;
	private int deadline = 20 * 600;
	private int targetEntity;
	private int pinnedDesk;
	private T3Office.Spot pinnedGoal;
	private net.minecraft.world.phys.Vec3 sharedPosition;
	private String targetThread;
	private String chosen;
	private boolean inWorld;
	private int questionSeenAt;
	private int doneAt;
	private boolean sawDrink;
	private boolean built;
	private final boolean flow;
	private final boolean sharedOffice;

	private SelfTest(T3CraftClient mod, String prompt) {
		this.mod = mod;
		this.prompt = prompt;
		this.step = Step.WAIT_WORLD;
		this.flow = prompt.startsWith("officeflow:");
		this.sharedOffice = prompt.startsWith("sharedoffice:");
	}

	static SelfTest fromSystemProperty(T3CraftClient mod) {
		String prompt = System.getProperty("t3craft.selftest");
		return prompt == null || prompt.isBlank() ? null : new SelfTest(mod, prompt);
	}

 private int visualTick;
 private void officeVisual(Minecraft mc) {
  if(mc.player==null||mc.level==null||!mod.state().snapshot().connected()){if(ticks>600){T3CraftClient.LOGGER.error("OFFICE_VISUAL failed to load world/fixture; closing test client");mc.stop();}return;}
  mc.options.pauseOnLostFocus=false;
  visualTick++;
  if(visualTick<1250 && mc.gui.screen()!=null)mc.gui.setScreen(null);
  if(visualTick==40){mc.gui.setScreen(null);mod.buildOffice();}
  var o=mod.officeOrigin(); if(o==null)return;
  if(visualTick==100){visualCommand(mc,"difficulty peaceful");visualCommand(mc,"time set day");}
  if(visualTick==700)visualCamera(mc,o,42,18,40,14,7,11);
  if(visualTick==750)shot(mc,"office-exterior");
  if(visualTick==800)visualCamera(mc,o,12,.8,9,6,1.1,3);
  if(visualTick==850)shot(mc,"office-interior");
  if(visualTick==860)visualCamera(mc,o,21,.7,6,21,1.3,0);
  if(visualTick==890)shot(mc,"office-kitchenette");
  if(visualTick==900){var a=mod.residents().brainForTest().agents().values().stream().filter(v->v.status==T3State.Status.NEEDS_YOU).findFirst().orElseThrow();visualCamera(mc,o,a.x-1.5,a.y()+.5,a.z-2,a.x,a.y()+1.5,a.z);}
  if(visualTick==940){
   var a=mod.residents().brainForTest().agents().values().stream().filter(v->v.status==T3State.Status.NEEDS_YOU).findFirst().orElseThrow();
   mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,new net.minecraft.world.phys.Vec3(o.getX()+a.x,o.getY()+a.y()+1.5,o.getZ()+a.z));
   if(mc.gui.hud.isHidden())mc.gui.hud.toggle();
  }
  if(visualTick==950){
   var a=mod.residents().brainForTest().agents().values().stream().filter(v->v.status==T3State.Status.NEEDS_YOU).findFirst().orElseThrow();
   for(var entry:mod.residents().threadsByEntity().entrySet())if(entry.getValue().equals(a.threadId)&&mc.level.getEntity(entry.getKey()) instanceof Mannequin m){float angle=Math.abs(net.minecraft.util.Mth.wrapDegrees(m.getYHeadRot()-m.yBodyRot));float toPlayer=(float)(Math.toDegrees(Math.atan2(mc.player.getZ()-m.getZ(),mc.player.getX()-m.getX()))-90);float facing=Math.abs(net.minecraft.util.Mth.wrapDegrees(m.getYHeadRot()-toPlayer));if(angle>50.1||facing>10)throw new IllegalStateException("Needs-you agent not facing the player: head "+angle+" from body, "+facing+" from the player");T3CraftClient.LOGGER.info("OFFICE_VISUAL PASS needs-you agent faces the player (head {} from body, {} off)",angle,facing);}
   shot(mc,"office-needs-you");
  }
  if(visualTick==1000){visualCommand(mc,"time set midnight");var server=mc.getSingleplayerServer();server.execute(()->{for(String command:T3Office.lamps(1,true))server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),T3Office.absolute(command,o.getX(),o.getY(),o.getZ()));});visualCamera(mc,o,12,.8,9,6,1.1,3);}
  if(visualTick==1050)shot(mc,"office-night");
  if(visualTick==1100)visualCamera(mc,o,12,6.8,9,6,7.1,3);
  if(visualTick==1150)shot(mc,"office-upper-floor");
  if(visualTick==1200){
   var agents=mod.residents().brainForTest().agents();long lower=agents.values().stream().filter(a->a.floor==0).count(),upper=agents.values().stream().filter(a->a.floor==1).count();
   long laptops=0;java.util.Set<java.util.UUID> ids=new java.util.HashSet<>();for(var entity:mc.level.entitiesForRendering())if(entity.entityTags().contains("t3craft_laptop")){laptops++;if(!ids.add(entity.getUUID()))throw new IllegalStateException("Duplicate desk UUID");}
   if(lower!=8||upper!=4||laptops!=24)throw new IllegalStateException("Office roster/pairs "+lower+"/"+upper+"/"+laptops);
   if(!mc.level.getBlockState(o.offset(15,5,10)).is(net.minecraft.world.level.block.Blocks.BIRCH_PLANKS))throw new IllegalStateException("Upper floor was not placed");
   long litter=mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,OfficePlacement.bounds(o,2)).size();
   if(litter!=0)throw new IllegalStateException("Office build dropped "+litter+" items");
   T3CraftClient.LOGGER.info("OFFICE_VISUAL PASS structure, eight lower/four upper agents, twelve unique laptop pairs");
   var a=agents.values().stream().filter(v->v.status==T3State.Status.NEEDS_YOU).findFirst().orElseThrow();targetThread=a.threadId;
   var desk=OfficeDesign.floor().getAsJsonArray("work").get(a.desk).getAsJsonObject().getAsJsonArray("desk");double x=desk.get(0).getAsDouble()+.5,z=desk.get(1).getAsDouble()+.5;
   visualCamera(mc,o,x,a.floor*6+.6,z-2.5,x,a.floor*6+1.25,z);
  }
  if(visualTick==1250){
   if(!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit)||!hit.getEntity().getUUID().equals(DeskLaptops.uuid(targetThread,true)))throw new IllegalStateException("Crosshair missed laptop interaction");
   net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
  }
  if(visualTick==1280){
   if(!(mc.gui.screen() instanceof T3Screen)||!targetThread.equals(mod.state().focusedThreadId()))throw new IllegalStateException("Laptop route: screen="+mc.gui.screen()+" focused="+mod.state().focusedThreadId()+" expected="+targetThread);
   T3CraftClient.LOGGER.info("OFFICE_VISUAL PASS actual laptop use-key opens its own thread");shot(mc,"office-laptop-thread");
  }
  if(visualTick==1320){mc.gui.setScreen(null);visualCamera(mc,o,180,5,180,190,5,190);}
  if(visualTick==1360)mc.player.connection.sendCommand("t3 office go");
  if(visualTick==1400){
   if(mc.player.distanceToSqr(o.getX()+25.5,o.getY(),o.getZ()-4.5)>.3||Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()))>1)throw new IllegalStateException("Office go missed entrance or door direction");
   T3CraftClient.LOGGER.info("OFFICE_VISUAL PASS /t3 office go from distant unloaded area");shot(mc,"office-arrival");
   visualCamera(mc,o,160,4,160,170,4,170);
  }
  if(visualTick==1440)mc.player.connection.sendCommand("t3 office");
  if(visualTick==1480){if(mc.player.distanceToSqr(o.getX()+25.5,o.getY(),o.getZ()-4.5)>.3)throw new IllegalStateException("Bare office command did not return");T3CraftClient.LOGGER.info("OFFICE_VISUAL PASS bare /t3 office returns without rebuilding");mc.stop();}


 }
 /**
  * Captures the simple native UI at the current GUI scale in an existing office world against the office fixture
  * ({@code scripts/office/fixture.py}): aim at the agent that needs you and click it (card), type into the card,
  * the laptop on a finished thread, the laptop on the waiting thread (Approve / Deny), a typed message, the
  * message sent, then the laptop once more at GUI scale 2.
  */
 private int nativeTick;
 private static final String NATIVE_MESSAGE = "Add a dark mode toggle to the settings page";
 private void nativeUiVisual(Minecraft mc) {
  if (mc.player == null || mc.level == null || !mod.state().snapshot().connected()) { if (ticks > 1200) { T3CraftClient.LOGGER.error("NATIVE_UI failed to load world/fixture; closing test client"); mc.stop(); } return; }
  mc.options.pauseOnLostFocus = false;
  nativeTick++;
  var o = mod.officeOrigin();
  if (o == null) { if (nativeTick > 400) { T3CraftClient.LOGGER.error("NATIVE_UI no office in this world"); mc.stop(); } return; }
  if (nativeTick == 20) { visualCommand(mc, "difficulty peaceful"); visualCommand(mc, "time set 1000"); visualCommand(mc, "weather clear"); visualCommand(mc, "gamemode creative"); }
  if (nativeTick == 300) {
   var a = mod.residents().brainForTest().agents().values().stream().filter(v -> v.status == T3State.Status.NEEDS_YOU).findFirst().orElseThrow();
   targetThread = a.threadId;
   // Agent positions are design coordinates: go through the office's frame, so a turned T3 World office works too.
   var f = mod.officeFrame();
   var eye = f.point(a.x - 1.4, a.y() + .3, a.z - 2.0);
   visualCameraWorld(mc, eye.x, eye.y, eye.z, f.point(a.x, a.y() + 1.5, a.z));
  }
  if (nativeTick == 340) {
   var a = mod.residents().brainForTest().agents().get(targetThread);
   mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, mod.officeFrame().point(a.x, a.y() + 1.4, a.z));
   if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
  }
  if (nativeTick == 370) shot(mc, "native-agent-aim");
  if (nativeTick == 380) {
   if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit) || !targetThread.equals(mod.residents().threadsByEntity().get(hit.getEntity().getId()))) throw new IllegalStateException("Crosshair missed the waiting agent: " + mc.hitResult);
   net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
  }
  if (nativeTick == 420) {
   if (!(mc.gui.screen() instanceof T3AgentCardScreen card) || !card.approvalVisibleForTest()) throw new IllegalStateException("Agent click did not open the card with its approval: " + mc.gui.screen());
   T3CraftClient.LOGGER.info("NATIVE_UI PASS use-key on the waiting agent opens the native card with Approve / Deny");
   shot(mc, "native-agent-card");
  }
  if (nativeTick == 440) ((T3AgentCardScreen) mc.gui.screen()).setInputForTest("Looks good, run the full suite too");
  if (nativeTick == 470) shot(mc, "native-agent-card-typing");
  if (nativeTick == 480) { ((T3AgentCardScreen) mc.gui.screen()).setInputForTest(""); mc.gui.setScreen(null); mod.focus("office-6"); }
  if (nativeTick == 500) mod.openLaptop();
  if (nativeTick == 540) {
   if (!(mc.gui.screen() instanceof T3Screen)) throw new IllegalStateException("Laptop did not open the native screen: " + mc.gui.screen());
   shot(mc, "native-laptop");
  }
  if (nativeTick == 560) ((T3Screen) mc.gui.screen()).selectThreadForTest(targetThread);
  if (nativeTick == 600) {
   if (!((T3Screen) mc.gui.screen()).requestCardVisibleForTest()) throw new IllegalStateException("Waiting thread shows no Approve / Deny");
   shot(mc, "native-laptop-needs-you");
  }
  if (nativeTick == 620) { T3Screen s = (T3Screen) mc.gui.screen(); s.selectThreadForTest("office-6"); s.setComposerForTest(NATIVE_MESSAGE); }
  if (nativeTick == 660) shot(mc, "native-laptop-typing");
  if (nativeTick == 670) ((T3Screen) mc.gui.screen()).typeAndSubmit(NATIVE_MESSAGE, true);
  if (nativeTick == 740) {
   var focus = mod.state().snapshot().focus();
   if (focus == null || focus.messages().stream().noneMatch(m -> "user".equals(m.role()) && NATIVE_MESSAGE.equals(m.text()))) throw new IllegalStateException("Sent message did not come back from the fixture: " + lastMessage(mod.state().snapshot()));
   T3CraftClient.LOGGER.info("NATIVE_UI PASS message sent from the laptop and shown as the player's");
   shot(mc, "native-laptop-sent");
  }
  if (nativeTick == 760) { mc.options.guiScale().set(2); mc.resizeGui(); }
  if (nativeTick == 800) shot(mc, "native-laptop-scale2");
  if (nativeTick == 810) { mc.options.guiScale().set(3); mc.resizeGui(); }
  if (nativeTick == 850) shot(mc, "native-laptop-scale3");
  if (nativeTick == 860) { mc.options.guiScale().set(5); mc.resizeGui(); mc.gui.setScreen(null); }
  if (nativeTick == 880) { T3CraftClient.LOGGER.info("NATIVE_UI PASS all captures taken"); mc.stop(); }
 }
 private static void visualCommand(Minecraft mc,String command){var server=mc.getSingleplayerServer();if(server!=null)server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),command));}
 private static void visualCamera(Minecraft mc,net.minecraft.core.BlockPos o,double x,double y,double z,double tx,double ty,double tz){
  visualCameraWorld(mc,o.getX()+x,o.getY()+y,o.getZ()+z,new net.minecraft.world.phys.Vec3(o.getX()+tx,o.getY()+ty,o.getZ()+tz));
 }
 /** Stands the player at a world position (feet) looking at a world point, flying, HUD hidden. */
 private static void visualCameraWorld(Minecraft mc,double x,double y,double z,net.minecraft.world.phys.Vec3 target){
  mc.gui.setScreen(null);if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();
  double dx=target.x-x,dy=target.y-(y+1.62),dz=target.z-z;
  double yaw=Math.toDegrees(Math.atan2(dz,dx))-90,pitch=-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
  visualCommand(mc,"tp Player "+x+" "+y+" "+z+" "+yaw+" "+pitch);

  mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();
 }

 private void skinSpike(Minecraft mc) {
  if (mc.player == null || mc.level == null) return;
  if (!built) {
   built = true;
   mc.gui.setScreen(null);
   for (int i=0;i<2;i++) {
    var mannequin = net.minecraft.world.entity.EntityTypes.MANNEQUIN.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
    var json = new com.google.gson.JsonObject();
    json.addProperty("texture", "t3craft:entity/agent/claude");
    json.addProperty("model", "wide");
    if(i==1) { json.add("id", net.minecraft.core.UUIDUtil.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, java.util.UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")).getOrThrow()); json.addProperty("name", "Steve"); }
    mannequin.setComponent(net.minecraft.core.component.DataComponents.PROFILE, net.minecraft.world.item.component.ResolvableProfile.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,json).getOrThrow());
    mannequin.setId(-7800000-i); mannequin.setNoGravity(true);
    mannequin.setCustomName(net.minecraft.network.chat.Component.literal(i==0?"Texture only":"UUID and texture")); mannequin.setCustomNameVisible(true);
    mannequin.snapTo(mc.player.getX()+(i==0?-1.2:1.2),mc.player.getY(),mc.player.getZ()+4,180,0);
    mc.level.addEntity(mannequin);
   }
   mc.player.setYRot(0); mc.player.setXRot(0);
   ticks=0;
  }
  if(ticks==200) shot(mc,"skin-spike");
 }

	private static void press(net.minecraft.client.gui.screens.Screen screen, String label) {
		var button = screen.children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(label))
			.map(c -> (net.minecraft.client.gui.components.Button) c).findFirst().orElseThrow();
		if (!button.active) throw new IllegalStateException("QA button disabled: " + label);
		click(screen, button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2);
	}

	private void fixtureControl(Minecraft minecraft, String action) {
		mod.state().run(() -> {
			// Protocol 2 fixture A; -Dt3craft.fixturePort (-PfixturePort) moves it when the fixtures run on allocated ports.
			var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + Integer.getInteger("t3craft.fixturePort", 25682) + "/_qa/" + action))
				.timeout(java.time.Duration.ofSeconds(3)).GET().build();
			var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() != 200) throw new java.io.IOException("Fixture control failed");
		}, e -> minecraft.execute(() -> finish(minecraft, "FAIL fixture recovery control")));
	}

	void tick(Minecraft minecraft) {
		ticks++;
		if (prompt.equals("office-a")) { officeVisual(minecraft); return; }
		if (prompt.equals("nativeui")) { nativeUiVisual(minecraft); return; }
		if (prompt.equals("skin-spike")) { skinSpike(minecraft); return; }
		if (step == Step.DONE) return;
		if (--deadline == 0) finish(minecraft, "FAIL timed out at " + step);
		T3State.Snapshot snapshot = mod.state().snapshot();
		T3State.ThreadRow row = snapshot.focusedRow();
		switch (step) {
			case HIDDEN_DECISION -> {
				var entry = mod.state().decisions().stream().filter(e -> "vis-subagent".equals(e.thread().id()) && e.requestId() != null).findFirst().orElse(null);
				if (entry == null) return;
				mod.openRequest(entry);
				advance(Step.HIDDEN_CARD, "opened hidden child Decision through client screen path");
			}
			case HIDDEN_CARD -> {
				if (ticks < 40 || snapshot.focus() == null || !"vis-subagent".equals(snapshot.focus().threadId())) return;
				if (!(minecraft.gui.screen() instanceof T3Screen screen) || !screen.requestCardVisibleForTest()
					|| snapshot.threads().stream().anyMatch(r -> "vis-subagent".equals(r.id()))) {
					finish(minecraft, "FAIL hidden child request card missing or child leaked into sidebar"); return;
				}
				mod.focus("vis-settled");
				advance(Step.HIDDEN_SETTLED, "hidden child card rendered; opening settled conversation");
			}
			case HIDDEN_SETTLED -> {
				if (ticks < 40 || row == null || !"vis-settled".equals(row.id()) || snapshot.focus() == null) return;
				if (snapshot.focus().messages().isEmpty() || snapshot.threads().stream().anyMatch(r -> "vis-settled".equals(r.id()))) {
					finish(minecraft, "FAIL settled conversation missing or leaked into sidebar"); return;
				}
				finish(minecraft, "PASS hidden Decision screen renders request card and settled focus retains conversation");
			}
			case STABLE_SITE -> {
				if (ticks < 220 || mod.office().building()) return;
				OfficeBrain.Agent pin = mod.residents().brainForTest().agents().get("A-done-0");
				if (pin == null || !mod.residents().brainForTest().agents().containsKey("A-waiting") || !mod.residents().brainForTest().agents().containsKey("B-waiting")) {
					finish(minecraft, "FAIL pinned/waiting residents missing"); return;
				}
				pinnedDesk = pin.desk; pinnedGoal = pin.goal; targetEntity = mannequinFor(minecraft, "A-done-0").getId();
				fixtureControl(minecraft, "churn"); advance(Step.STABLE_CHURN, "six newer threads arrived");
			}
			case STABLE_CHURN -> {
				if (ticks < 60 || snapshot.threads().size() != 32) return;
				OfficeBrain.Agent pin = mod.residents().brainForTest().agents().get("A-done-0");
				if (pin == null || pin.desk != pinnedDesk || !pin.goal.equals(pinnedGoal) || mannequinFor(minecraft, "A-done-0").getId() != targetEntity) {
					finish(minecraft, "FAIL churn moved pinned desk or replaced mannequin"); return;
				}
				T3Screen screen = (T3Screen) minecraft.gui.screen();
				// The simple laptop has no project filter; the list shows every active thread.
				if (screen.sidebarCountForTest() == 0 || !"A-done-0".equals(mod.state().focusedThreadId()) || !"Office QA draft".equals(screen.composerValueForTest())) {
					finish(minecraft, "FAIL project filter changed focus/draft or mixed projects"); return;
				}
				advance(Step.STABLE_CAPTURE, "project filter kept focused thread and draft");
			}
			case STABLE_CAPTURE -> {
				if (ticks < 10) return;
				shot(minecraft, "stable-office-project-filter");
				fixtureControl(minecraft, "auth-failed"); advance(Step.STABLE_OFFLINE, "checking pinned identity through reconnect");
			}
			case STABLE_OFFLINE -> {
				if (ticks < 60 || mod.state().online("A-done-0")) return;
				Mannequin pin = mannequinFor(minecraft, "A-done-0");
				if (pin == null || pin.getId() != targetEntity || pin.getCustomName() == null || !pin.getCustomName().getString().startsWith("Offline")) {
					finish(minecraft, "FAIL offline pinned resident identity/label"); return;
				}
				shot(minecraft, "pinned-connection-recovery"); fixtureControl(minecraft, "reset");
				advance(Step.STABLE_RECOVER, "offline pin retained with explicit offline label");
			}
			case STABLE_RECOVER -> {
				if (ticks < 40 || !mod.state().online("A-done-0")) return;
				OfficeBrain.Agent pin = mod.residents().brainForTest().agents().get("A-done-0");
				if (pin == null || pin.desk != pinnedDesk || mannequinFor(minecraft, "A-done-0").getId() != targetEntity || !(minecraft.gui.screen() instanceof T3Screen screen) || !"Office QA draft".equals(screen.composerValueForTest())) {
					finish(minecraft, "FAIL reconnect changed pinned desk/entity or draft"); return;
				}
				T3CraftClient.LOGGER.info("SELFTEST saved pinned desk {}", pinnedDesk);
				finish(minecraft, "PASS stable office: pinned/waiting residents, same desk/entity through recency churn and reconnect, offline label, owner project filter and preserved draft");
			}
			case STABLE_RESTORE -> {
				if (ticks < 40) return;
				OfficeBrain.Agent pin = mod.residents().brainForTest().agents().get("A-done-0");
				int expected = Integer.getInteger("t3craft.expectedDesk", -1);
				if (pin == null || pin.desk != expected || !mod.officePreferences().pins.stream().anyMatch(p -> p.threadId().equals("A-done-0"))) {
					finish(minecraft, "FAIL restarted client lost pinned desk"); return;
				}
				minecraft.gui.setScreen(new T3PinsScreen(mod)); fixtureControl(minecraft, "archive-pin");
				advance(Step.STABLE_ARCHIVE, "restart restored exact desk; checking unavailable pin removal");
			}
			case STABLE_ARCHIVE -> {
				if (ticks < 60 || snapshot.threads().stream().anyMatch(r -> r.id().equals("A-done-0"))) return;
				if (mod.residents().brainForTest().agents().containsKey("A-done-0") || !(minecraft.gui.screen() instanceof T3PinsScreen screen)) {
					finish(minecraft, "FAIL archived resident remained"); return;
				}
				shot(minecraft, "unavailable-pin");
				click(screen, screen.width - 104, 56); // Unavailable Open is disabled.
				if (!(minecraft.gui.screen() instanceof T3PinsScreen)) { finish(minecraft, "FAIL archived pin reopened a thread"); return; }
				click(screen, screen.width - 48, 56);
				if (!mod.officePreferences().pins.isEmpty()) { finish(minecraft, "FAIL unavailable pin cannot be removed"); return; }
				finish(minecraft, "PASS restarted office: exact pinned desk restored, archived resident removed, unavailable pin safely unpinned");
			}
			case SHARED_STABLE -> {
				if (ticks < 500) return;
				Mannequin pin = sharedMannequin(minecraft, "A-done-0");
				if (pin == null) { finish(minecraft, "FAIL shared pin missing"); return; }
				sharedPosition = pin.position(); fixtureControl(minecraft, "churn");
				advance(Step.SHARED_CHURN, "operator-owned resident settled; churning threads");
			}
			case SHARED_CHURN -> {
				if (ticks < 160 || snapshot.threads().size() != 32) return;
				Mannequin pin = sharedMannequin(minecraft, "A-done-0");
				if (pin == null || pin.position().distanceToSqr(sharedPosition) > 0.001 || !mod.officePreferences().pins.isEmpty()) {
					finish(minecraft, "FAIL shared pin moved or leaked into client preferences"); return;
				}
				shot(minecraft, "shared-stable-office");
				ClientCommands.send("tp @s " + pin.getX() + " " + Math.ceil(pin.getY()) + " " + (pin.getZ() + 2.5));
				advance(Step.SHARED_TARGET, "checking shared resident without a local village");
			}
			case SHARED_TARGET -> {
				if (ticks < 20) return;
				Mannequin pin = sharedMannequin(minecraft, "A-done-0");
				if (pin == null) { finish(minecraft, "FAIL shared resident vanished"); return; }
				minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, pin.getEyePosition());
				if (!(minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit) || !hit.getEntity().getUUID().equals(pin.getUUID())) {
					if (ticks > 140) { shot(minecraft, "shared-resident-target-failure"); finish(minecraft, "FAIL shared resident ray"); } return;
				}
				net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				advance(Step.SHARED_OPEN, "physical shared mannequin click");
			}
			case SHARED_OPEN -> {
				if (ticks < 30) return;
				if (!(minecraft.gui.screen() instanceof T3Screen) || !"A-done-0".equals(mod.state().focusedThreadId())) { finish(minecraft, "FAIL shared mannequin interaction without local anchor"); return; }
				shot(minecraft, "shared-resident-panel"); minecraft.gui.setScreen(null);
				ClientCommands.send("t3office unpin A-done-0");
				advance(Step.SHARED_UNPIN, "shared resident opened its owned thread");
			}
			case SHARED_UNPIN -> {
				if (ticks < 60) return;
				if (sharedMannequin(minecraft, "A-done-0") != null) { finish(minecraft, "FAIL shared unpin did not release old resident"); return; }
				finish(minecraft, "PASS shared office: operator pin survives newer threads, client preferences independent, physical resident opens its owned thread without local village; operator unpin removes displaced resident");
			}
			case AUTH_ERROR -> {
				String error = mod.state().connectionError("A-done-0");
				if (ticks < 30 || error == null || !error.contains("Settings → Environments")) return;
				if (!(minecraft.gui.screen() instanceof T3Screen screen) || !"Connection QA draft".equals(screen.composerValueForTest()) || mod.state().online("A-done-0") || !mod.state().online("B-done-0")) {
					finish(minecraft, "FAIL auth recovery scope/draft"); return;
				}
				shot(minecraft, "pairing-recovery"); fixtureControl(minecraft, "reset");
				advance(Step.AUTH_RECOVER, "visible re-pair guidance with cached conversation retained");
			}
			case AUTH_RECOVER -> {
				if (ticks < 30 || !mod.state().online("A-done-0")) return;
				fixtureControl(minecraft, "protocol3");
				advance(Step.PROTOCOL_ERROR, "checking future protocol after server restart");
			}
			case PROTOCOL_ERROR -> {
				String error = mod.state().connectionError("A-done-0");
				if (ticks < 30 || error == null || !error.contains("Update T3 Craft")) return;
				shot(minecraft, "protocol-recovery"); fixtureControl(minecraft, "reset");
				advance(Step.PROTOCOL_RECOVER, "visible incompatible-protocol guidance");
			}
			case PROTOCOL_RECOVER -> {
				if (ticks < 30 || !mod.state().online("A-done-0")) return;
				if (!(minecraft.gui.screen() instanceof T3Screen screen) || !"Connection QA draft".equals(screen.composerValueForTest())) {
					finish(minecraft, "FAIL recovery lost draft"); return;
				}
				finish(minecraft, "PASS connection recovery: per-machine auth and protocol guidance, healthy peer, reconnect and preserved draft");
			}

			case RELEASE_SITE -> {
				if (mod.office().building() || ticks < 240) return;
				var o = mod.officeOrigin();
				if (ticks == 240) view(minecraft, o, 10, 0, 19.5, 12.5, 0.8, 19.5);
				if (ticks == 270) minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
					new net.minecraft.world.phys.Vec3(o.getX() + 12.5, o.getY() + 0.8, o.getZ() + 19.5));
				if (ticks < 280) return;
				// A moving resident can cross the ray. Do not turn that physical click into a thread-panel click.
				boolean onLectern = minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
					&& hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
					&& hit.getBlockPos().equals(o.offset(12, 0, 19));
				if (!onLectern) {
					if (ticks == 280) T3CraftClient.LOGGER.info("SELFTEST waiting for lectern ray: {}", minecraft.hitResult == null ? "none" : minecraft.hitResult.getType());
					if (ticks > 400) { shot(minecraft, "lectern-entry-failure"); finish(minecraft, "FAIL packaged lectern ray"); }
					return;
				}
				net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				advance(Step.RELEASE_OPEN, "opening packaged review through the lectern");
			}
			case RELEASE_OPEN -> {
				if (ticks < 40) return;
				if (!(minecraft.gui.screen() instanceof T3ReviewScreen screen)) {
					shot(minecraft, "lectern-entry-failure");
					finish(minecraft, "FAIL packaged lectern entry: " + (minecraft.gui.screen() == null ? "no screen" : minecraft.gui.screen().getClass().getSimpleName())); return;
				}
				if (screen.reviewForTest() == null) return;
				if (screen.filesForTest().size() != 2 || !screen.reviewForTest().diff().contains("+hello, Minecraft")) {
					finish(minecraft, "FAIL packaged live checkpoint patch"); return;
				}
				screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(30, 85,
					new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
				advance(Step.RELEASE_CHECK, "real T3 checkpoint loaded in the packaged client");
			}
			case RELEASE_CHECK -> {
				if (ticks < 30 || !(minecraft.gui.screen() instanceof T3ReviewScreen screen)) return;
				shot(minecraft, "release-live-checkpoint");
				mod.saveDraft(snapshot.focusedId(), "Release QA notes");
				screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(screen.width - 77, screen.height - 20,
					new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
				advance(Step.RELEASE_FEEDBACK, "returning from packaged review to the composer");
			}
			case RELEASE_FEEDBACK -> {
				if (ticks < 30) return;
				if (!(minecraft.gui.screen() instanceof T3Screen screen) || !"Release QA notes".equals(screen.composerValueForTest())) {
					finish(minecraft, "FAIL packaged feedback draft"); return;
				}
				finish(minecraft, "PASS packaged release: live T3 connection, lectern entry, real checkpoint patch and preserved feedback draft");
			}

			case DESK_SITE -> {
				if (mod.office().building() || ticks < 240) return;
				var o = mod.officeOrigin();
				if (ticks == 240) view(minecraft, o, 4.5, 1.1, 27.5, 2.5, 3.4, 27.5);
				if (ticks < 280) return;
				shot(minecraft, "decision-whiteboard");
				net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				advance(Step.DESK_OPEN, "right-clicking the decision whiteboard");
			}
			case DESK_OPEN -> {
				if (ticks < 40) return;
				if (!(minecraft.gui.screen() instanceof T3DeskScreen screen)) {
					finish(minecraft, "FAIL whiteboard did not open decision desk"); return;
				}
				if (mod.state().decisions().stream().filter(e -> e.requestId() != null).count() != 2) {
					finish(minecraft, "FAIL two-machine decisions not loaded"); return;
				}
				shot(minecraft, "decision-desk");
				screen.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
				advance(Step.DESK_REQUEST, "opening oldest request beyond eight visible residents");
			}
			case DESK_REQUEST -> {
				if (ticks < 40 || snapshot.focus() == null) return;
				if (!(minecraft.gui.screen() instanceof T3Screen screen) || !"A-waiting".equals(snapshot.focus().threadId())) {
					finish(minecraft, "FAIL decision targeted another thread"); return;
				}
				shot(minecraft, "decision-request");
				screen.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_Y, 0, 0));
				advance(Step.DESK_ANSWERED, "answering the fixture approval through Y");
			}
			case DESK_ANSWERED -> {
				if (ticks < 40) return;
				if (mod.state().decisions().stream().anyMatch(e -> e.thread().id().equals("A-waiting") && e.requestId() != null)) return;
				if (mod.state().decisions().stream().noneMatch(e -> e.thread().id().equals("B-waiting") && e.requestId() != null)) {
					finish(minecraft, "FAIL answering A removed B's question"); return;
				}
				minecraft.gui.setScreen(null);
				mod.focus("A-done-0");
				var o = mod.officeOrigin();
				view(minecraft, o, 10.0, 0.0, 19.5, 12.5, 0.8, 19.5);
				advance(Step.DESK_REVIEW_OPEN, "one decision remains; aiming at review lectern");
			}
			case DESK_REVIEW_OPEN -> {
				if (ticks == 30) {
					var o = mod.officeOrigin();
					minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
						new net.minecraft.world.phys.Vec3(o.getX() + 12.5, o.getY() + 0.8, o.getZ() + 19.5));
				}
				if (ticks < 40) return;
				shot(minecraft, "review-lectern");
				T3CraftClient.LOGGER.info("SELFTEST lectern hit {}", minecraft.hitResult);
				net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				advance(Step.DESK_REVIEW, "right-clicking review lectern");
			}
			case DESK_REVIEW -> {
				if (ticks < 40) return;
				if (!(minecraft.gui.screen() instanceof T3ReviewScreen screen)) {
					finish(minecraft, "FAIL lectern did not open checkpoint review"); return;
				}
				if (screen.reviewForTest() == null) return;
				if (screen.filesForTest().size() != 2) { finish(minecraft, "FAIL checkpoint file list"); return; }
				shot(minecraft, "checkpoint-reply");
				screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(30, 85, new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
				advance(Step.DESK_DIFF, "completed checkpoint loaded through RPC");
			}
			case DESK_DIFF -> {
				if (ticks == 30) shot(minecraft, "checkpoint-diff");
				if (ticks == 40 && minecraft.gui.screen() instanceof T3ReviewScreen screen) screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(30, 105, new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
				if (ticks == 70) shot(minecraft, "checkpoint-deleted-file");
				if (ticks >= 80 && minecraft.gui.screen() instanceof T3ReviewScreen screen) {
					mod.saveDraft("A-done-0", "Unsent review notes");
					screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(screen.width - 77, screen.height - 20,
						new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
					advance(Step.DESK_FEEDBACK, "returning to the composer with an existing draft");
				}
			}

			case DESK_FEEDBACK -> {
				if (ticks < 30) return;
				if (!(minecraft.gui.screen() instanceof T3Screen screen) || !"A-done-0".equals(snapshot.focusedId())
					|| !"Unsent review notes".equals(screen.composerValueForTest())) {
					finish(minecraft, "FAIL feedback did not preserve the correct thread's draft"); return;
				}
				shot(minecraft, "checkpoint-feedback-draft");
				screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(50, screen.height - 25,
					new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
				advance(Step.DESK_SIDEBAR, "opening Decisions below a long sidebar");
			}

			case DESK_SIDEBAR -> {
				if (ticks < 20) return;
				if (!(minecraft.gui.screen() instanceof T3DeskScreen) || mod.state().decisions().size() != 1
					|| !"B-waiting".equals(mod.state().decisions().getFirst().thread().id())) {
					finish(minecraft, "FAIL sidebar decision button or resolved queue"); return;
				}
				shot(minecraft, "decision-one-remaining");
				finish(minecraft, "PASS decision desk and checkpoint review: whiteboard, exact approval, preserved second-machine question, lectern, patch file clicks, deleted file, unsent feedback draft and sidebar decision button");
			}

			case SILK_WORLD -> {
				if (ticks == 40) {
					// The office goes just south of here; spawn is ten blocks further back, facing the facade (yaw 0 = south).
					ClientCommands.send("tp @s ~ ~ ~ 0 0");
					mod.buildOffice();
				if (!mod.office().building()) { finish(minecraft, "FAIL plain-field office was not accepted in one step"); return; }
					ClientCommands.send("setworldspawn ~ ~ ~-10 0 0");
					ClientCommands.send("tp @s ~ ~ ~-10 0 -12");
					built = true;
				}
				if (ticks <= 45) return;
				if (mod.office().building()) {
					built = true;
					return;
				}
				if (!built) return;
				if (ticks % 20 != 0) return;
				int agents = mod.residents().brainForTest().agents().size();
				if (agents == 0) return;
				shot(minecraft, "silk-world");
				finish(minecraft, "PASS silk world: office at " + mod.officeOrigin().toShortString() + ", " + agents + " agents, floors " + mod.floors());
			}
			case OFFICE_SITE -> {
				if (ticks < 60) return;
				if (sharedOffice) {
					advance(Step.OFFICE_SHARED_PAIR, "fresh ground for the server office");
					return;
				}
				mod.buildOffice();
				if (!mod.office().building()) { finish(minecraft, "FAIL plain-field office was not accepted in one step"); return; }
				advance(Step.OFFICE, "building the office at " + minecraft.player.blockPosition().toShortString());
			}
			case OFFICE -> {
				// Only the first build: later lamp/board updates also queue commands.
				if (!built && mod.office().building()) {
					ticks = 0;
					return;
				}
				built = true;
				var o = mod.officeOrigin();
				if (flow && ticks == 20) {
					// Stand at the work tables, looking at the whiteboard end.
					minecraft.player.getAbilities().flying = true;
					minecraft.player.onUpdateAbilities();
					view(minecraft, o, 11, 1.6, 8, 3, 1, 18);
				}
				if (flow && ticks >= 60) {
					advance(Step.FLOW_SEND, "office built at " + o.toShortString() + "; floors " + mod.floors());
					return;
				}
				if (flow) return;
				if (ticks == 20) {
					// Out front, far enough back to see the facade and the whole sign board.
					minecraft.player.getAbilities().flying = true;
					minecraft.player.onUpdateAbilities();
					view(minecraft, o, 7, 18, -48, 7, 22, -2);
				}
				if (ticks == 60) shot(minecraft, "office-front");
				// Down the length of the room from the kitchen end, like the photo toward the window.
				if (ticks == 70) view(minecraft, o, 7, 4.6, 0.6, 7, 1, 29);
				if (ticks == 110) shot(minecraft, "office-length-walking");
				if (ticks == 330) shot(minecraft, "office-length");
				// Behind the sofa, looking at the lounge and window.
				if (ticks == 340) view(minecraft, o, 7, 3.3, 20.5, 7, 0.8, 30);
				if (ticks == 380) shot(minecraft, "office-lounge");
				// From the window end back toward the kitchen.
				if (ticks == 390) view(minecraft, o, 12, 4.2, 27, 3, 0.8, 0);
				if (ticks == 410) shot(minecraft, "office-kitchen");
				// The whiteboard, when a thread needs you.
				if (ticks == 415) view(minecraft, o, 6, 2.8, 25.2, 1, 2.8, 27.9);
				if (ticks == 430) shot(minecraft, "office-whiteboard");
				// Outside the suite door, then a real right-click on it.
				if (ticks == 435) view(minecraft, o, 10.5, 0, -5.5, 10.5, 1.2, -2);
				if (ticks == 455) {
					var door = new net.minecraft.core.BlockPos(o.getX() + 10, o.getY(), o.getZ() - 2);
					var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(door),
						net.minecraft.core.Direction.NORTH, door, false);
					minecraft.gameMode.useItemOn(minecraft.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
				}
				if (ticks == 470) {
					var state = minecraft.level.getBlockState(new net.minecraft.core.BlockPos(o.getX() + 10, o.getY(), o.getZ() - 2));
					T3CraftClient.LOGGER.info("SELFTEST door after right-click: {}", state);
					shot(minecraft, "office-door");
				}
				// Up the ladder to the second machine's floor.
				if (ticks == 480 && mod.floors().size() > 1) view(minecraft, o, 12, T3Office.FLOOR_HEIGHT + 1.6, 3, 5, T3Office.FLOOR_HEIGHT + 1, 20);
				if (ticks == 510 && mod.floors().size() > 1) shot(minecraft, "office-floor2");
				// Seated workers, close up.
				if (ticks == 515) view(minecraft, o, 11, 2.4, 6, 6, 1, 13);
				if (ticks == 540) shot(minecraft, "office-seats");
				// Night: the lamps as they come on after New York sunset.
				if (ticks == 545) {
					ClientCommands.send("time set midnight");
					mod.office().send(o, T3Office.lamps(mod.floors().size(), true));
					view(minecraft, o, 7, 4.6, 0.6, 7, 1, 29);
				}
				if (ticks == 590) shot(minecraft, "office-night");
				if (ticks == 595) {
					ClientCommands.send("time set day");
					mod.office().send(o, T3Office.lamps(mod.floors().size(), NycSun.isNight(java.time.Instant.now())));
				}
				if (ticks >= 600) {
					T3CraftClient.LOGGER.info("SELFTEST office agents per floor: {}", perFloor());
					T3CraftClient.LOGGER.info("SELFTEST RESULT PASS (office); game left running");
					step = Step.DONE;
				}
			}
			case FLOW_SEND -> {
				if (ticks == 1) mod.openPanel();
				if (ticks < 20) return;
				if (minecraft.gui.screen() instanceof T3Screen screen) screen.typeAndSubmit(prompt.substring(11).strip(), false);
				advance(Step.FLOW_WAIT, "sent from the panel; back in the office");
			}
			case FLOW_WAIT -> {
				if (row == null) return;
				if (ticks == 60) shot(minecraft, "flow-working");
				String detail = mod.state().waitingDetail(row.id());
				if (row.status() != T3State.Status.NEEDS_YOU || detail == null || detail.isEmpty()) return;
				OfficeBrain.Agent waiting = mod.residents().brainForTest().agents().get(row.id());
				if (waiting == null || waiting.zone != OfficeBrain.Zone.NEEDS_YOU || !waiting.settled()) return;
				var o = mod.officeOrigin();
				if (ticks % 20 != 0) return;
				// Wait for the brain to reach the board and the board to be written.
				var board = minecraft.level.getBlockEntity(new net.minecraft.core.BlockPos(o.getX() + 2, o.getY() + 3, o.getZ() + 27));
				String text = board instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign
					? sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).stream().map(c -> c.getString()).reduce("", (a, b) -> a + b + " ").strip() : "";
				if (text.isEmpty()) return;
				T3CraftClient.LOGGER.info("SELFTEST whiteboard detail sign: '{}' (waiting detail '{}')", text, detail);
				view(minecraft, o, 6, 2.8, 25.2, 1, 2.8, 27.9);
				advance(Step.FLOW_CLICK, "needs you: " + detail);
			}
			case FLOW_CLICK -> {
				if (ticks == 30) shot(minecraft, "flow-whiteboard");
				if (ticks == 35) {
					// Look at the waiting agent and right-click it like a player would.
					var mannequin = mannequinFor(minecraft, row.id());
					if (mannequin == null) {
						finish(minecraft, "FAIL no agent for the waiting thread");
						return;
					}
					minecraft.player.getAbilities().flying = false;
					minecraft.player.onUpdateAbilities();
					ClientCommands.send("tp @s " + (mannequin.getX() + 2) + " " + (mannequin.getY() - mannequin.getY() % 1) + " " + mannequin.getZ());
				}
				if (ticks == 42) {
					var mannequin = mannequinFor(minecraft, row.id());
					minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, mannequin.getEyePosition());
				}
				if (ticks == 50) net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				if (ticks == 70) {
					if (!(minecraft.gui.screen() instanceof T3Screen) || snapshot.focus() == null || snapshot.focus().approvals().isEmpty()) {
						finish(minecraft, "FAIL right-click did not open the approval (screen " + minecraft.gui.screen() + ")");
						return;
					}
					shot(minecraft, "flow-approval");
				}
				if (ticks == 80) {
					var approval = snapshot.focus().approvals().getFirst();
					mod.respond(approval, "accept");
					minecraft.gui.setScreen(null);
					// Watch the walk from the work tables to the fridge and the sofa.
					view(minecraft, mod.officeOrigin(), 12, 3.2, 12, 3, 0.5, 6);
					advance(Step.FLOW_DONE, "approved " + approval.detail() + " from the agent");
				}
			}
			case FLOW_DONE -> {
				if (row == null) return;
				OfficeBrain.Agent agent = mod.residents().brainForTest().agents().get(row.id());
				if (agent != null && agent.pause > 0 && !sawDrink) {
					sawDrink = true;
					shot(minecraft, "flow-fridge");
				}
				if (agent != null && agent.holding && agent.settled() && doneAt == 0) {
					doneAt = ticks;
					view(minecraft, mod.officeOrigin(), 7, 2.6, 20.5, 7, 0.8, 26);
				}
				if (doneAt > 0 && ticks - doneAt == 30) shot(minecraft, "flow-sofa");
				if (doneAt > 0 && ticks - doneAt >= 40) {
					var book = minecraft.player.getInventory().getNonEquipmentItems().stream()
						.filter(stack -> stack.is(net.minecraft.world.item.Items.WRITTEN_BOOK)).findFirst().orElse(null);
					if (book == null) {
						if (ticks - doneAt < 200) return;
						finish(minecraft, "FAIL no report book after DONE");
						return;
					}
					var content = book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
					T3CraftClient.LOGGER.info("SELFTEST book: '{}' with {} pages; first page: {}", content.title().raw(), content.pages().size(),
						content.pages().getFirst().raw().getString());
					minecraft.gui.setScreen(new net.minecraft.client.gui.screens.inventory.BookViewScreen(
						net.minecraft.client.gui.screens.inventory.BookViewScreen.BookAccess.fromItem(book)));
					advance(Step.FINISH, "fridge drink " + sawDrink + ", book delivered");
				}
			}
			case OFFICE_SHARED_PAIR -> {
				if (ticks == 1) {
					ClientCommands.send("t3office off");
					ClientCommands.send("t3office pair " + prompt.substring(prompt.indexOf(':') + 1).strip());
				}
				if (ticks == 200) {
					ClientCommands.send("t3office build");
					advance(Step.OFFICE_SHARED_BUILD, "paired the server and built the shared office");
				}
			}
			case OFFICE_SHARED_BUILD -> {
				if (ticks == 100) {
					var feet = minecraft.player.blockPosition();
					var o = new net.minecraft.core.BlockPos(feet.getX() - 10, feet.getY(), feet.getZ() + 5);
					minecraft.player.getAbilities().flying = true;
					minecraft.player.onUpdateAbilities();
					view(minecraft, o, 7, 4.6, 0.6, 7, 1, 29);
				}
				if (ticks == 300) {
					shot(minecraft, "shared-office");
					advance(Step.OFFICE_SHARED_CHECK, "looking for server agents");
				}
			}
			case OFFICE_SHARED_CHECK -> {
				if (ticks == 10) {
					var names = new java.util.ArrayList<String>();
					Mannequin target = null;
					for (var entity : minecraft.level.entitiesForRendering()) {
						if (entity instanceof Mannequin mannequin && mannequin.getId() > 0 && mannequin.getCustomName() != null) {
							names.add(mannequin.getCustomName().getString());
							if (target == null || mannequin.distanceTo(minecraft.player) < target.distanceTo(minecraft.player)) target = mannequin;
						}
					}
					T3CraftClient.LOGGER.info("SELFTEST shared office server agents: {}", names);
					if (target == null) {
						finish(minecraft, "FAIL no server-side office agents visible");
						return;
					}
					targetEntity = target.getId();
					// Empty hand (a held book would open instead), walk up, then right-click the real mannequin.
					minecraft.player.getInventory().setSelectedSlot(8);
					ClientCommands.send("tp @s " + (target.getX() + 1.5) + " " + target.getY() + " " + target.getZ());
				}
				if (ticks == 40 && minecraft.level.getEntity(targetEntity) instanceof Mannequin target) {
					minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
				}
				if (ticks == 45) net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				if (ticks == 70) {
					String name = minecraft.level.getEntity(targetEntity) instanceof Mannequin target ? target.getCustomName().getString() : "?";
					var focused = snapshot.focusedRow();
					boolean ok = minecraft.gui.screen() instanceof T3Screen && focused != null
						&& minecraft.level.getEntity(targetEntity) instanceof Mannequin target && OfficeBrain.agentUuid(focused.id()).equals(target.getUUID());
					shot(minecraft, "shared-panel");
					T3CraftClient.LOGGER.info("SELFTEST shared click on '{}' opened panel for '{}'", name, focused == null ? null : focused.title());
					if (!ok) finish(minecraft, "FAIL right-click on a server mannequin did not open its thread");
				}
				if (ticks == 90) finish(minecraft, "PASS (shared office)");
			}
			case THREADS -> {
				if (!(minecraft.gui.screen() instanceof T3Screen screen)) return;
				// All machines, then each machine, scrolled to the bottom, with a screenshot of each.
				if (ticks == 20) {
					T3CraftClient.LOGGER.info("SELFTEST threads: All machines = {}; oldest thread revealed on open: {}",
						screen.sidebarCountForTest(), screen.focusedRowVisibleForTest());
					if (!screen.focusedRowVisibleForTest()) finish(minecraft, "FAIL focused thread not scrolled into view");
					shot(minecraft, "threads-all");
				}
				if (ticks == 30 || ticks == 60) {
					String machine = screen.cycleMachineForTest();
					T3CraftClient.LOGGER.info("SELFTEST threads: {} = {}", machine, screen.sidebarCountForTest());
				}
				if (ticks == 40 || ticks == 70) shot(minecraft, "threads-filtered");
				if (ticks == 45 || ticks == 75) screen.scrollSidebarForTest(100);
				if (ticks == 50 || ticks == 80) shot(minecraft, "threads-bottom");
				if (ticks >= 90) finish(minecraft, "PASS (threads)");
			}
			case PREP -> {
				// Test world only: daylight, creative, and hover above the canopy looking slightly down,
				// so whatever the agent builds "in front of me" is in the screenshot.
				if (ticks == 1) {
					ClientCommands.send("time set day");
					ClientCommands.send("weather clear");
					ClientCommands.send("gamemode creative");
				}
				if (ticks == 30) {
					minecraft.player.getAbilities().flying = true;
					minecraft.player.onUpdateAbilities();
					ClientCommands.send("tp @s ~ ~6 ~ ~ 18");
				}
				if (ticks >= 30 && ticks % 10 == 0 && !minecraft.player.getAbilities().flying) {
					minecraft.player.getAbilities().flying = true;
					minecraft.player.onUpdateAbilities();
				}
				if (ticks >= 70) advance(Step.OPEN, "hovering at y=" + minecraft.player.blockPosition().getY());
			}
			case DONE_PANEL -> {
				if (ticks == 10) mod.openPanel();
				// After the done toast has faded, so it doesn't sit over the panel header.
				if (ticks == 150) shot(minecraft, "done-panel");
				if (ticks >= 160) advance(Step.FINISH, "panel captured");
			}
			case WAIT_WORLD -> {
				if ("hidden-decisions".equals(prompt)) {
					if (minecraft.player == null || !snapshot.connected()) return;
					fixtureControl(minecraft, "visibility");
					mod.state().setPanelOpen(true);
					advance(Step.HIDDEN_DECISION, "loading hidden child fixture"); return;
				}
				if (minecraft.player != null && snapshot.connected() && !snapshot.threads().isEmpty() && ticks > 100 && prompt.startsWith("new:")) {
					advance(Step.PREP, "world ready; clearing a view for the build");
				} else if (minecraft.player != null && snapshot.connected() && !snapshot.threads().isEmpty() && ticks > 100) {
					advance(Step.OPEN, "world ready, " + snapshot.threads().size() + " threads, focused " + (row == null ? "none" : row.title()));
				}
			}
			case OPEN -> {
				if (prompt.startsWith("stable-office") || "stable-shared".equals(prompt)) {
					if (ticks < 160) return;
					if (!snapshot.threads().stream().anyMatch(t -> t.id().equals("A-done-0") && t.projectTitle().equals("Greeting fixture"))) {
						finish(minecraft, "FAIL stable office tests require isolated fixtures"); return;
					}
					if ("stable-office-restore".equals(prompt)) { advance(Step.STABLE_RESTORE, "checking saved identity after client restart"); return; }
					if ("stable-shared".equals(prompt)) {
						ClientCommands.send("t3office build");
						ClientCommands.send("t3office pin A-done-0");
						advance(Step.SHARED_STABLE, "building operator-owned shared office"); return;
					}
					mod.focus("A-done-0"); mod.openPanel();
					T3Screen screen = (T3Screen) minecraft.gui.screen(); screen.setComposerForTest("Office QA draft");
					mod.togglePin(mod.state().snapshot().focusedRow());
					if (!mod.pinned(snapshot.threads().stream().filter(r -> r.id().equals("A-done-0")).findFirst().orElseThrow())) {
						finish(minecraft, "FAIL Pin desk button"); return;
					}
					ClientCommands.send("gamemode creative"); mod.buildOffice();
					if (!mod.office().building()) { finish(minecraft, "FAIL plain-field office was not accepted in one step"); return; }
					advance(Step.STABLE_SITE, "pinned old thread through panel and built office"); return;
				}

				if ("connection-errors".equals(prompt)) {
					if (ticks < 160) return; // Let Minecraft's first-join chat warning leave the screenshot.
					if (snapshot.threads().size() != 26 || !snapshot.threads().stream().anyMatch(t -> t.id().equals("A-done-0") && t.projectTitle().equals("Greeting fixture"))) {
						finish(minecraft, "FAIL connection-errors requires isolated fixtures"); return;
					}
					mod.focus("A-done-0"); mod.openPanel();
					((T3Screen) minecraft.gui.screen()).setComposerForTest("Connection QA draft");
					fixtureControl(minecraft, "auth-failed"); advance(Step.AUTH_ERROR, "simulating revoked fixture pairing");
					return;
				}
				String wanted = System.getProperty("t3craft.focus");
				if (wanted != null) {
					snapshot.threads().stream().filter(t -> t.title().toLowerCase().contains(wanted.toLowerCase()))
						.findFirst().ifPresent(t -> mod.focus(t.id()));
				}

				if ("desk-review".equals(prompt) || "release-review".equals(prompt)) {
					ClientCommands.send("gamemode creative");
					ClientCommands.send("time set day");
					ClientCommands.send("weather clear");
					mod.buildOffice();
				if (!mod.office().building()) { finish(minecraft, "FAIL plain-field office was not accepted in one step"); return; }
					advance("release-review".equals(prompt) ? Step.RELEASE_SITE : Step.DESK_SITE, "building in the isolated QA world");
					return;
				}

				if ("threads".equals(prompt)) {
					// Focus the oldest thread first, so opening has to scroll to reveal it.
					mod.focus(snapshot.threads().getLast().id());
					mod.openPanel();
					advance(Step.THREADS, "panel opened for the thread list");
					return;
				}
				if ("watch".equals(prompt)) {
					// Local test world: daylight, creative, and let the agent under test build.
					ClientCommands.send("time set day");
					ClientCommands.send("gamemode creative");
					// Above the canopy, looking down ahead, so screenshots show what gets built.
					ClientCommands.send("tp @s ~ ~14 ~ ~ 40");
					mod.setAgentCommands(true);
					advance(Step.WATCH, "watching; screenshot every 15s");
					return;
				}
				if ("silkworld".equals(prompt)) {
					// One-time setup of a showcase world: the office right here at spawn, door just ahead.
					ClientCommands.send("gamemode creative");
					ClientCommands.send("time set day");
					ClientCommands.send("weather clear");
					advance(Step.SILK_WORLD, "building the office at spawn");
					return;
				}
				if ("office".equals(prompt) || flow || sharedOffice) {
					ClientCommands.send("time set day");
					ClientCommands.send("weather clear");
					ClientCommands.send("gamemode creative");
					// Fresh ground away from earlier builds.
					ClientCommands.send("spreadplayers ~160 ~ 1 20 false @s");
					advance(Step.OFFICE_SITE, "moving to fresh ground");
					return;
				}
				if ("anchor".equals(prompt)) {
					// Which office this world has (each world keeps its own).
					var anchor = mod.officeOrigin();
					finish(minecraft, "PASS anchor " + (anchor == null ? "none" : anchor.toShortString()));
					return;
				}
				mod.openPanel();
				advance(Step.SEND, "panel opened");
			}
			case SEND -> {
				if (ticks < 40) return;
				shot(minecraft, "panel");
				if ("look".equals(prompt)) {
					advance(Step.CLOSE, "look only; nothing sent");
					step = Step.LOOK;
					return;
				}
				if (prompt.startsWith("ask:")) {
					if (minecraft.gui.screen() instanceof T3Screen screen) screen.typeAndSubmit(prompt.substring(4).strip(), true);
					advance(Step.ASK_WAIT, "asked; waiting for the agent's question");
					return;
				}
				boolean fresh = prompt.startsWith("new:");
				String text = fresh ? prompt.substring(4).strip() : prompt;

				if (minecraft.gui.screen() instanceof T3Screen screen) {
					if (fresh) screen.startNewThread();
					// A new-thread run behaves like a real Enter: send and go back to the world.
					screen.typeAndSubmit(text, !fresh);
				}
				inWorld = fresh;
				advance(Step.WAIT_WORKING, (fresh ? "new thread: " : "sent: ") + text);
			}
			case WAIT_WORKING -> {
				if (row != null && row.status() != T3State.Status.DONE) advance(Step.WAIT_APPROVAL_OR_DONE, "status " + row.status());
				else if (ticks > 200) advance(Step.WAIT_APPROVAL_OR_DONE, "never saw working (fast turn)");
			}
			case WAIT_APPROVAL_OR_DONE -> {
				if (ticks == 30) shot(minecraft, "working");
				if (row == null) return;
				if (row.status() == T3State.Status.NEEDS_YOU && snapshot.focus() != null && !snapshot.focus().approvals().isEmpty()) {
					if (ticks < 40) return;
					shot(minecraft, inWorld ? "needs-you" : "approval");
					var approval = snapshot.focus().approvals().getFirst();
					if (approval.detail() != null && approval.detail().startsWith("mcp__") && !approval.detail().startsWith("mcp__minecraft__")) {
						finish(minecraft, "FAIL unexpected approval: " + approval.detail());
						return;
					}
					mod.respond(approval, "accept");
					advance(Step.WAIT_DONE, "approved " + approval.detail());
				} else if (row.status() == T3State.Status.DONE && ticks > 40) {
					advance(Step.WAIT_DONE, "done without approval");
				}
			}
			case WAIT_DONE -> {
				if (row != null && row.status() == T3State.Status.NEEDS_YOU && snapshot.focus() != null
					&& !snapshot.focus().approvals().isEmpty() && ticks % 20 == 0) {
					var approval = snapshot.focus().approvals().getFirst();
					if (approval.detail() == null || !approval.detail().startsWith("mcp__minecraft__")) {
						finish(minecraft, "FAIL unexpected approval: " + approval.detail());
						return;
					}
					mod.respond(approval, "accept");
					T3CraftClient.LOGGER.info("SELFTEST approved {}", approval.detail());
				}
				if (inWorld && row != null && row.status() == T3State.Status.DONE && doneAt == 0) {
					doneAt = ticks;
					// The [agent] command log is useful while playing but hides the build in a still image.
					minecraft.gui.hud.getChat().clearMessages(false);
					// Frame the build: look at the beacon the agent placed, from a little above.
					var center = minecraft.player.blockPosition();
					for (var pos : net.minecraft.core.BlockPos.betweenClosed(center.offset(-16, -12, -16), center.offset(16, 4, 16))) {
						if (minecraft.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.BEACON)) {
							minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
								net.minecraft.world.phys.Vec3.atCenterOf(pos).add(0, -1.5, 0));
							break;
						}
					}
				}
				// ~3 s: long enough for the beacon beam to form, while the done toast is still up.
				if (inWorld && doneAt > 0 && ticks - doneAt >= 60) {
					shot(minecraft, "done");
					advance(Step.DONE_PANEL, "done in world: " + lastMessage(snapshot));
					return;
				}
				if (inWorld) return;
				if (row != null && row.status() == T3State.Status.DONE && ticks > 40) {
					shot(minecraft, "done-panel");
					advance(Step.CLOSE, "done: " + lastMessage(snapshot));
				}
			}
			case ASK_WAIT -> {
				var focus = snapshot.focus();
				if (focus == null || focus.userInputs().isEmpty() || ticks < 20) return;
				var question = focus.userInputs().getFirst().questions().getFirst();
				if (questionSeenAt == 0) {
					questionSeenAt = ticks;
					return;
				}
				if (ticks - questionSeenAt < 15) return;
				shot(minecraft, "question");
				if (question.options().size() < 2) {
					finish(minecraft, "FAIL question has fewer than 2 options");
					return;
				}
				chosen = question.options().get(1).label();
				// Real number-key path on the panel.
				if (minecraft.gui.screen() instanceof T3Screen screen) screen.pressOption(2);
				advance(Step.ASK_ANSWERED, "question \"" + question.question() + "\" → pressed 2 (" + chosen + ")");
			}
			case ASK_ANSWERED -> {
				if (row == null || row.status() != T3State.Status.DONE || ticks < 40) return;
				String reply = lastMessage(snapshot);
				shot(minecraft, "answered");
				boolean ok = reply.toLowerCase().contains(chosen.toLowerCase());
				T3CraftClient.LOGGER.info("SELFTEST reply: {}", reply);
				finish(minecraft, ok ? "PASS" : "FAIL reply does not mention " + chosen);
			}
			case LOOK -> {
				if (ticks == 1 && minecraft.gui.screen() instanceof T3Screen screen) screen.openPicker();
				if (ticks == 30) shot(minecraft, "picker");
				if (ticks < 50) return;
				minecraft.gui.setScreen(null);
				T3CraftClient.LOGGER.info("SELFTEST RESULT PASS (look); game left running");
				step = Step.DONE;
			}
			case DONE -> {
			}
			case WATCH -> {
				deadline = Integer.MAX_VALUE;
				if (ticks == 20) {
					minecraft.player.getAbilities().flying = true;
					minecraft.player.onUpdateAbilities();
				}
				if (ticks % 300 == 0) shot(minecraft, "watch");
			}
			case CLOSE -> {
				if (ticks < 20) return;
				minecraft.gui.setScreen(null);
				advance(Step.HUD, "panel closed");
			}
			case HUD -> {
				// While the done toast is still up.
				if (ticks < 20) return;
				shot(minecraft, "hud");
				advance(Step.FINISH, "hud captured");
			}
			case FINISH -> {
				if (flow && ticks == 30) shot(minecraft, "flow-book");
				if (ticks <= 40) return;
				if (prompt.startsWith("new:")) {
					// Real-environment demo: leave the game running for the player.
					T3CraftClient.LOGGER.info("SELFTEST RESULT PASS; game left running");
					step = Step.DONE;
				} else {
					finish(minecraft, "PASS");
				}
			}
		}
	}

	private static void click(net.minecraft.client.gui.screens.Screen screen, double x, double y) {
		screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y,
			new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
	}
	private static Mannequin sharedMannequin(Minecraft minecraft, String thread) {
		for (var entity : minecraft.level.entitiesForRendering()) if (entity instanceof Mannequin v && OfficeBrain.agentUuid(thread).equals(v.getUUID())) return v;
		return null;
	}

	private Mannequin mannequinFor(Minecraft minecraft, String threadId) {
		// The client-side Mannequin standing in for a thread in this world's office.
		for (var entry : mod.residents().threadsByEntity().entrySet()) {
			if (entry.getValue().equals(threadId) && minecraft.level.getEntity(entry.getKey()) instanceof Mannequin mannequin) return mannequin;
		}
		return null;
	}

	private static void view(Minecraft minecraft, net.minecraft.core.BlockPos o, double x, double y, double z, double tx, double ty, double tz) {
		ClientCommands.send("tp @s " + (o.getX() + x) + " " + (o.getY() + y) + " " + (o.getZ() + z)
			+ " facing " + (o.getX() + tx) + " " + (o.getY() + ty) + " " + (o.getZ() + tz));
	}

	private String perFloor() {
		var counts = new java.util.TreeMap<Integer, Integer>();
		for (OfficeBrain.Agent agent : mod.residents().brainForTest().agents().values()) counts.merge(agent.floor, 1, Integer::sum);
		return counts.toString();
	}

	private void advance(Step next, String note) {
		T3CraftClient.LOGGER.info("SELFTEST {} -> {}: {}", step, next, note);
		step = next;
		ticks = 0;
	}

	private static String lastMessage(T3State.Snapshot snapshot) {
		if (snapshot.focus() == null || snapshot.focus().messages().isEmpty()) return "(none)";
		return snapshot.focus().messages().getLast().text();
	}

	private static void shot(Minecraft minecraft, String label) {
		T3CraftClient.LOGGER.info("SELFTEST screenshot {}", label);
		Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(),
			message -> T3CraftClient.LOGGER.info("SELFTEST {} {}", label, message.getString()));
	}

	private void finish(Minecraft minecraft, String result) {
		step = Step.DONE;
		T3CraftClient.LOGGER.info("SELFTEST RESULT {}", result);
		minecraft.stop();
	}
}
