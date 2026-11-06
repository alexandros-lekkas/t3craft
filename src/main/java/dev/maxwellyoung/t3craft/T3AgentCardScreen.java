package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Clicking an agent in the office: a small card beside it over the lightly dimmed world. The agent's name
 * and provider, its status, the last message (or the request it is waiting on), one input box, Approve / Deny
 * when needed, and "Open on laptop" for the whole thread. Enter in the box sends and returns to the world (on a
 * focused button it presses that button); Esc, the panel key (unless it is a letter) or a click outside close it.
 */
final class T3AgentCardScreen extends Screen {
	private static final int WIDTH = 190;
	private static final int PAD = 6;
	private static final int BUTTON = 18;
	private static final int INPUT = 18;
	private static final int MAX_LINES = 6;
	/** The card shows the start of the last message; parsing more than this only to drop it is wasted work. */
	private static final int MAX_BODY_CHARS = 2000;

	private final T3CraftClient mod;
	private final String threadId;
	private final long openedAt = System.currentTimeMillis();
	private EditBox input;
	private Button approve;
	private Button deny;
	private Button open;
	private final ComposerDraft draft = new ComposerDraft();

	// Laid out every tick and frame from the latest snapshot.
	private int x0, y0, x1, y1;
	private T3State.ThreadRow row;
	private T3State.Approval approval;
	private String bodyLabel = "";
	private int bodyLabelColor = T3Ui.MUTED;
	private List<FormattedCharSequence> body = List.of();
	/** The wrapped body for one focus snapshot and width: layout runs every tick and frame, the snapshot changes far less. */
	private T3State.Focus cachedFocus;
	private int cachedWidth = -1;
	private boolean cachedLoaded;
	private List<FormattedCharSequence> cachedLines = List.of();

	T3AgentCardScreen(T3CraftClient mod, String threadId) {
		super(Component.literal("T3 agent"));
		this.mod = mod;
		this.threadId = threadId;
	}

	@Override
	protected void init() {
		mod.state().setPanelOpen(true);
		boolean first = input == null;
		input = new EditBox(font, 0, 0, cardWidth() - 2 * PAD, INPUT, input, Component.literal("Reply"));
		input.setMaxLength(8000);
		input.setHint(Component.literal("Reply…").withColor(0xFF7A7A7A));
		if (first) {
			input.setValue(mod.draft(threadId));
			draft.load(threadId);
		}
		addRenderableWidget(input);
		setInitialFocus(input);
		int half = (cardWidth() - 2 * PAD - 4) / 2;
		approve = addRenderableWidget(Button.builder(Component.literal("Approve (Y)"), b -> answer("accept")).bounds(0, 0, half, BUTTON).build());
		deny = addRenderableWidget(Button.builder(Component.literal("Deny (N)"), b -> answer("decline")).bounds(0, 0, half, BUTTON).build());
		open = addRenderableWidget(Button.builder(Component.literal("Open on laptop"), b -> { parkDraft(); mod.openLaptop(); })
			.bounds(0, 0, cardWidth() - 2 * PAD, BUTTON).build());
		layout();
	}

	@Override
	public void removed() {
		parkDraft();
		mod.state().setPanelOpen(false);
	}

	private void parkDraft() {
		draft.park(threadId, input.getValue(), mod::draft, mod::saveDraft);
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, width, height, T3Ui.DIM);
	}

	@Override
	public void tick() {
		layout();
	}

	private int cardWidth() { return Math.min(WIDTH, width - 12); }

	/** The card sits to the right of the agent the player just clicked (the agent is under the crosshair). */
	private void layout() {
		T3State.Snapshot snapshot = mod.state().snapshot();
		row = snapshot.threads().stream().filter(r -> r.id().equals(threadId)).findFirst().orElse(null);
		if (row == null && snapshot.focusedRow() != null && snapshot.focusedRow().id().equals(threadId)) row = snapshot.focusedRow();
		T3State.Focus focus = snapshot.focus() != null && snapshot.focus().threadId().equals(threadId) ? snapshot.focus() : null;
		boolean online = mod.state().online(threadId);
		int textWidth = cardWidth() - 2 * PAD;

		approval = focus == null || focus.approvals().isEmpty() ? null : focus.approvals().getFirst();
		if (approval != null) {
			bodyLabel = "Approve " + approval.kind() + "?";
			bodyLabelColor = T3Ui.NEEDS_YOU;
		} else if (focus != null && focus.needsInput()) {
			T3State.Question question = focus.userInputs().getFirst().questions().getFirst();
			bodyLabel = question.header() == null ? "Question" : question.header();
			bodyLabelColor = T3Ui.NEEDS_YOU;
		} else if (focus != null && !focus.messages().isEmpty()) {
			bodyLabel = "user".equals(focus.messages().getLast().role()) ? "You" : row == null ? "Agent" : T3Ui.provider(mod, row);
			bodyLabelColor = T3Ui.MUTED;
		} else {
			bodyLabel = focus == null ? "Loading…" : row != null && row.status() == T3State.Status.WORKING ? "Working…" : "No messages yet.";
			bodyLabelColor = T3Ui.MUTED;
		}
		List<FormattedCharSequence> lines = bodyLines(focus, textWidth);

		// Short windows (GUI scale 5–6 at 720p) drop body lines before the buttons fall off the screen.
		int chrome = PAD + font.lineHeight + 2 + font.lineHeight + 5 + font.lineHeight + 5
			+ INPUT + 4 + (approval != null ? BUTTON + 4 : 0) + BUTTON + PAD;
		int maxLines = Math.max(0, Math.min(MAX_LINES, (height - 12 - chrome) / font.lineHeight));
		body = lines.size() > maxLines ? lines.subList(0, maxLines) : lines;

		int cardHeight = chrome + body.size() * font.lineHeight;
		int cardWidth = cardWidth();
		x0 = Math.min(width / 2 + 12, width - 6 - cardWidth);
		x1 = x0 + cardWidth;
		y0 = Math.max(6, (height - cardHeight) / 2);
		y1 = y0 + cardHeight;

		int y = y0 + PAD + font.lineHeight + 2 + font.lineHeight + 5 + font.lineHeight + body.size() * font.lineHeight + 5;
		input.setX(x0 + PAD);
		input.setY(y);
		input.setWidth(cardWidth - 2 * PAD);
		y += INPUT + 4;
		approve.visible = deny.visible = approval != null;
		// Inert while an answer is on its way, so a double click can't answer the next queued request unseen.
		approve.active = deny.active = online && !mod.approvalInFlight(focus);
		if (approval != null) {
			int half = (cardWidth - 2 * PAD - 4) / 2;
			approve.setX(x0 + PAD); approve.setY(y); approve.setWidth(half);
			deny.setX(x1 - PAD - half); deny.setY(y); deny.setWidth(half);
			y += BUTTON + 4;
		}
		open.setX(x0 + PAD);
		open.setY(y);
		open.setWidth(cardWidth - 2 * PAD);
	}

	/** The body text for this focus and width, wrapped once per snapshot rather than on every tick and frame. */
	private List<FormattedCharSequence> bodyLines(T3State.Focus focus, int textWidth) {
		if (cachedLoaded && focus == cachedFocus && textWidth == cachedWidth) return cachedLines;
		List<FormattedCharSequence> lines;
		T3State.Approval first = focus == null || focus.approvals().isEmpty() ? null : focus.approvals().getFirst();
		if (first != null) {
			lines = font.split(Component.literal(first.detail() == null ? "" : clip(first.detail())), textWidth);
		} else if (focus != null && focus.needsInput()) {
			T3State.Question question = focus.userInputs().getFirst().questions().getFirst();
			lines = font.split(Component.literal((question.question() == null ? "" : clip(question.question())) + "  (answer on the laptop)"), textWidth);
		} else if (focus != null && !focus.messages().isEmpty()) {
			T3State.Message last = focus.messages().getLast();
			List<T3Markdown.Line> rendered = new ArrayList<>();
			T3Markdown.render(font, clip(last.text()) + (last.streaming() ? " ▍" : ""), textWidth, T3Ui.TEXT, T3Ui.CODE, rendered);
			lines = rendered.stream().filter(l -> l.kind() != T3Markdown.Kind.GAP).map(T3Markdown.Line::text).limit(MAX_LINES).toList();
		} else {
			lines = List.of();
		}
		cachedFocus = focus;
		cachedWidth = textWidth;
		cachedLoaded = true;
		cachedLines = lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
		return cachedLines;
	}

	private static String clip(String text) {
		return text == null || text.length() <= MAX_BODY_CHARS ? text : text.substring(0, MAX_BODY_CHARS) + "…";
	}

	private void answer(String decision) {
		if (approval != null && approve.visible && approve.active) mod.respond(threadId, approval, decision);
		setFocused(input);
	}

	/** A failed send's text, back in this box when the card is for that thread; false leaves it to the saved draft. */
	boolean restoreFailedSend(String key, String text) {
		if (!threadId.equals(key)) return false;
		input.setValue(T3CraftClient.joinDraft(text, input.getValue()));
		return true;
	}

	private void send() {
		String text = input.getValue().trim();
		if (text.isEmpty()) return;
		mod.focus(threadId);
		T3CraftClient client = mod;
		String key = threadId;
		if (!mod.sendTo(threadId, text, () -> client.restoreFailedSend(key, text))) return;
		draft.sent(mod::saveDraft);
		input.setValue("");
		onClose();
	}

	/** Dev self-test. */
	void setInputForTest(String text) { input.setValue(text); }
	String inputValueForTest() { return input.getValue(); }
	boolean approvalVisibleForTest() { return approval != null; }
	boolean approvalActiveForTest() { return approve.visible && approve.active; }
	int bottomForTest() { return y1; }

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean empty = input.getValue().isEmpty();
		// Enter sends from the box; on a Tab-focused button it presses that button instead.
		if (event.isConfirmation() && (getFocused() == input || getFocused() == null)) { send(); return true; }
		// Y/N only when you are not typing: "yes, but…" in the reply box must never approve on its first letter.
		boolean typing = input.isFocused();
		boolean chord = event.hasControlDown() || event.hasAltDown() || (event.modifiers() & 0x8) != 0;
		if (empty && !typing && !chord && approval != null && (event.key() == InputConstants.KEY_Y || event.key() == InputConstants.KEY_N)) {
			answer(event.key() == InputConstants.KEY_Y ? "accept" : "decline");
			return true;
		}
		if (empty && mod.openKeyCloses() && mod.openKey().matches(event) && System.currentTimeMillis() - openedAt > 250) { onClose(); return true; }
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (input.getValue().isEmpty()) {
			int c = event.codepoint();
			if (mod.openKeyCloses() && mod.openKeyName().equalsIgnoreCase(Character.toString(c))) return true;
			if (approval != null && !input.isFocused() && (c == 'y' || c == 'n' || c == 'Y' || c == 'N')) return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.x() < x0 || event.x() >= x1 || event.y() < y0 || event.y() >= y1) { onClose(); return true; }
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		layout();
		T3Ui.panel(graphics, x0, y0, x1, y1);
		int x = x0 + PAD;
		int y = y0 + PAD;
		boolean online = mod.state().online(threadId);
		if (row != null) {
			graphics.text(font, T3Hud.ellipsize(font, row.title(), x1 - PAD - x), x, y, T3Ui.TITLE, false);
			y += font.lineHeight + 2;
			graphics.text(font, T3Ui.glyph(mod, row), x, y, 0xFFFFFFFF, false);
			String provider = T3Ui.provider(mod, row);
			graphics.text(font, provider, x + 11, y, T3Ui.TEXT, false);
			String status = T3Ui.status(row, online);
			T3Ui.dot(graphics, x + 11 + font.width(provider) + 8, y + 2, T3Ui.statusColor(row.status(), online));
			graphics.text(font, T3Hud.ellipsize(font, status, x1 - PAD - (x + 11 + font.width(provider) + 16)), x + 11 + font.width(provider) + 16, y, T3Ui.statusColor(row.status(), online), false);
		} else {
			graphics.text(font, "This agent's thread is gone.", x, y, T3Ui.MUTED, false);
			y += font.lineHeight + 2;
		}
		y += font.lineHeight + 5;
		graphics.text(font, bodyLabel, x, y, bodyLabelColor, false);
		y += font.lineHeight;
		for (FormattedCharSequence line : body) {
			graphics.text(font, line, x, y, T3Ui.TEXT, false);
			y += font.lineHeight;
		}
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}
}
