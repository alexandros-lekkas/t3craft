package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
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
 * The laptop: a plain Minecraft screen. Active threads on the left (title, status dot, provider glyph; snoozed
 * ones folded into one row, settled ones hidden), the selected thread on the right as wrapped text with one
 * input box and Send. When the agent needs you, Approve / Deny or the answer options sit above the input.
 * Enter sends and drops you back into the world; Shift+Enter sends and keeps the screen open; Y/N answer an
 * approval and 1–9 pick an answer.
 */
final class T3Screen extends Screen {
	static final int MARGIN = 6;
	private static final int PAD = 6;
	private static final int ROW = 13;
	private static final int HEADER = 12;
	private static final int BUTTON = 18;
	private static final int COMPOSER = 18;
	private static final int SEND_WIDTH = 36;
	private static final int MAX_OPTIONS = 9;

	private final T3CraftClient mod;
	private final long openedAt = System.currentTimeMillis();
	private EditBox composer;
	private Button send;
	private Button approve;
	private Button deny;
	private Button stop;
	private final List<Button> options = new ArrayList<>();
	private String selectedRequest;
	private T3State.Focus shownFocus;
	private boolean newThread;
	private final ComposerDraft composerDraft = new ComposerDraft();
	/** Conversation rows scrolled up from the bottom. */
	private int scroll;
	private int listScroll;
	private boolean snoozedExpanded;
	private final T3Scroll listWheel = new T3Scroll();
	private final T3Scroll chatWheel = new T3Scroll();

	// Answering the agent's questions: which request, which question, and the answers so far.
	private String answeringRequest;
	private int questionIndex;
	private JsonObject answers = new JsonObject();
	private final java.util.LinkedHashSet<String> multiPicked = new java.util.LinkedHashSet<>();
	/** Question sets already sent ("thread:request"), hidden until the snapshot drops them or the send fails. */
	private final java.util.Set<String> submittedInputs = new java.util.HashSet<>();

	private String anchorThread;
	private int anchorWidth;
	private int anchorCount;

	private T3State.Focus cachedFocus;
	private String cachedAgent;
	private int cachedWidth;
	private List<T3Markdown.Line> cachedLines = List.of();

	/** Laid out once per tick and frame: the request block above the composer, as [top, approval detail rows, question rows]. */
	private int blockTop;
	private List<FormattedCharSequence> approvalDetail = List.of();
	private List<FormattedCharSequence> questionText = List.of();
	private T3State.Approval shownApproval;
	private T3State.Question shownQuestion;

	T3Screen(T3CraftClient mod) { this(mod, null); }

	T3Screen(T3CraftClient mod, String selectedRequest) {
		super(Component.literal("T3 Code"));
		this.mod = mod;
		this.selectedRequest = selectedRequest;
	}

	// ----- Geometry (GUI pixels; sized to fit GUI scale 5 in a ~2000 px window, about 400 × 250) -----

	private int listWidth() { return Math.max(110, Math.min(170, width * 3 / 10)); }
	private int listLeft() { return MARGIN + PAD; }
	private int listRight() { return listLeft() + listWidth(); }
	private int listTop() { return MARGIN + PAD + HEADER + 3; }
	private int listBottom() { return height - MARGIN - PAD; }
	private int mainLeft() { return listRight() + PAD + 3; }
	private int mainRight() { return width - MARGIN - PAD; }
	private int mainTop() { return MARGIN + PAD + HEADER + 3; }
	private int composerY() { return height - MARGIN - PAD - COMPOSER; }
	private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW); }

	@Override
	protected void init() {
		mod.state().setPanelOpen(true);
		int x0 = mainLeft();
		int x1 = mainRight();
		int composerY = composerY();

		// A resize re-runs init and the new box copies the old one's text; only the first init loads the saved draft.
		boolean first = composer == null;
		composer = new EditBox(font, x0, composerY, x1 - x0 - SEND_WIDTH - 4, COMPOSER, composer, Component.literal("Message"));
		composer.setMaxLength(8000);
		if (first) loadDraft();
		updateHint();
		addRenderableWidget(composer);
		setInitialFocus(composer);
		send = addRenderableWidget(Button.builder(Component.literal("Send"), b -> submit(true))
			.bounds(x1 - SEND_WIDTH, composerY - 1, SEND_WIDTH, COMPOSER + 2).build());

		int half = (x1 - x0 - 4) / 2;
		approve = addRenderableWidget(Button.builder(Component.literal("Approve (Y)"), b -> answer("accept")).bounds(x0, 0, half, BUTTON).build());
		deny = addRenderableWidget(Button.builder(Component.literal("Deny (N)"), b -> answer("decline")).bounds(x1 - half, 0, half, BUTTON).build());
		options.clear();
		for (int i = 0; i < MAX_OPTIONS; i++) {
			int index = i;
			Button option = addRenderableWidget(Button.builder(Component.empty(), b -> pickOption(index)).bounds(x0, 0, x1 - x0, BUTTON).build());
			option.visible = false;
			options.add(option);
		}
		stop = addRenderableWidget(Button.builder(Component.literal("Stop"), b -> mod.interrupt()).bounds(x1 - 34, MARGIN + PAD - 3, 34, 14).build());
		layout(mod.state().snapshot());
		revealFocused();
	}

	/** Opening on a thread further down (a ping, an agent, /t3 open) scrolls the list to it. */
	private void revealFocused() {
		List<T3State.ThreadRow> rows = listRows();
		String focused = mod.state().focusedThreadId();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i) == null || !rows.get(i).id().equals(focused)) continue;
			int visible = visibleRows();
			if (i < listScroll || i >= listScroll + visible) listScroll = Math.max(0, i - visible / 2);
			clampListScroll(rows.size());
			return;
		}
	}

	@Override
	public void removed() {
		parkDraft();
		mod.state().setPanelOpen(false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		// The world keeps moving behind the panel.
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, width, height, T3Ui.DIM);
	}

	@Override
	public void tick() {
		layout(mod.state().snapshot());
		updateHint();
	}

	/** Rows of the list: active threads, then one "Snoozed" row (null) that unfolds the snoozed ones. */
	private List<T3State.ThreadRow> listRows() {
		List<T3State.ThreadRow> all = mod.state().snapshot().threads();
		List<T3State.ThreadRow> result = new ArrayList<>(all.stream().filter(r -> !T3Visibility.snoozedGroup(r)).toList());
		List<T3State.ThreadRow> snoozed = all.stream().filter(T3Visibility::snoozedGroup).toList();
		if (!snoozed.isEmpty()) { result.add(null); if (snoozedExpanded) result.addAll(snoozed); }
		return result;
	}

	private void clampListScroll(int total) {
		listScroll = Math.max(0, Math.min(listScroll, total - visibleRows()));
	}

	// ----- Drafts -----

	private String draftKey() {
		return newThread ? "new" : mod.state().focusedThreadId();
	}

	/** Every change of what the composer is for: park the text as the old target's draft, switch, load the new one's. */
	private void switchDraft(Runnable change) {
		parkDraft();
		change.run();
		loadDraft();
		updateHint();
	}

	private void loadDraft() {
		composer.setValue(mod.draft(draftKey()));
		composerDraft.load(draftKey());
	}

	private void parkDraft() {
		composerDraft.park(draftKey(), composer.getValue(), mod::draft, mod::saveDraft);
	}

	/** A failed send's text, back in this composer when it shows that draft; false leaves it to the saved draft. */
	boolean restoreFailedSend(String key, String text) {
		if ("new".equals(key) && !newThread && composer.getValue().isBlank()) switchDraft(() -> newThread = true);
		if (!java.util.Objects.equals(key, draftKey())) return false;
		composer.setValue(T3CraftClient.joinDraft(text, composer.getValue()));
		return true;
	}

	private void updateHint() {
		T3State.ThreadRow row = mod.state().snapshot().focusedRow();
		String hint = newThread
			? "New thread in " + (row == null ? "this project" : row.projectTitle()) + "…"
			: "Message " + (row == null ? "the agent" : T3Ui.provider(mod, row)) + "…";
		T3State.Question question = currentQuestion();
		if (question != null && !newThread) {
			hint = question.options().isEmpty() ? "Type your answer…"
				: "Press 1–" + Math.min(9, question.options().size()) + (question.allowCustomAnswer() ? ", or type your own answer" : "")
				+ (question.multiSelect() ? ", then Enter" : "") + "…";
		}
		composer.setHint(Component.literal(hint).withColor(0xFF7A7A7A));
	}

	// ----- Requests (approvals and questions) -----

	/** The chosen request only matters while it is pending; once answered, the next request shows. */
	private void dropAnsweredSelection(T3State.Focus focus) {
		if (selectedRequest == null || !focus.threadId().equals(mod.state().focusedThreadId())) return;
		boolean pending = focus.approvals().stream().anyMatch(a -> a.requestId().equals(selectedRequest))
			|| focus.userInputs().stream().anyMatch(i -> i.requestId().equals(selectedRequest));
		if (!pending) selectedRequest = null;
	}

	private T3State.Approval currentApproval() {
		T3State.Focus focus = shownFocus;
		if (focus == null) return null;
		dropAnsweredSelection(focus);
		if (focus.approvals().isEmpty()) return null;
		return selectedRequest == null ? focus.approvals().getFirst()
			: focus.approvals().stream().filter(a -> a.requestId().equals(selectedRequest)).findFirst().orElse(null);
	}

	private void answer(String decision) {
		T3State.Focus focus = shownFocus;
		T3State.Approval approval = currentApproval();
		// Inert while an answer is on its way: a second press must not answer the next queued request unseen.
		if (approval != null && approve.visible && approve.active) mod.respond(focus.threadId(), approval, decision);
		setFocused(composer);
	}

	private T3State.UserInput currentInput() {
		T3State.Focus focus = shownFocus;
		if (focus == null || focus.userInputs().isEmpty() || !mod.state().online(focus.threadId())) return null;
		dropAnsweredSelection(focus);
		String prefix = focus.threadId() + ":";
		submittedInputs.removeIf(key -> key.startsWith(prefix) && focus.userInputs().stream().noneMatch(i -> key.equals(prefix + i.requestId())));
		List<T3State.UserInput> open = focus.userInputs().stream().filter(i -> !submittedInputs.contains(prefix + i.requestId())).toList();
		if (open.isEmpty()) return null;
		T3State.UserInput input = selectedRequest == null ? open.getFirst()
			: open.stream().filter(i -> i.requestId().equals(selectedRequest)).findFirst().orElse(null);
		if (input == null) return null;
		if (!(focus.threadId() + ":" + input.requestId()).equals(answeringRequest)) {
			answeringRequest = focus.threadId() + ":" + input.requestId();
			questionIndex = 0;
			answers = new JsonObject();
			multiPicked.clear();
		}
		return input;
	}

	private T3State.Question currentQuestion() {
		T3State.UserInput input = currentInput();
		return input == null || questionIndex >= input.questions().size() ? null : input.questions().get(questionIndex);
	}

	/** Records one answer; after the last question, sends the whole set. */
	private void answerQuestion(com.google.gson.JsonElement value) {
		T3State.UserInput input = currentInput();
		T3State.Question question = currentQuestion();
		if (input == null || question == null) return;
		answers.add(question.id(), value);
		multiPicked.clear();
		questionIndex++;
		if (questionIndex >= input.questions().size()) {
			// Until the snapshot drops it, the request is still listed; don't start it over from question 1.
			String key = shownFocus.threadId() + ":" + input.requestId();
			submittedInputs.add(key);
			if (input.requestId().equals(selectedRequest)) selectedRequest = null;
			mod.answer(shownFocus.threadId(), input, answers, () -> submittedInputs.remove(key));
			answeringRequest = null;
		}
		updateHint();
	}

	private void pickOption(int index) {
		T3State.Question question = currentQuestion();
		if (question == null || index < 0 || index >= question.options().size()) return;
		String value = question.options().get(index).value();
		if (!question.multiSelect()) {
			answerQuestion(new com.google.gson.JsonPrimitive(value));
			return;
		}
		if (!multiPicked.remove(value)) multiPicked.add(value);
	}

	private void confirmMulti() {
		com.google.gson.JsonArray values = new com.google.gson.JsonArray();
		multiPicked.forEach(values::add);
		answerQuestion(values);
	}

	// ----- Dev self-test hooks -----

	/** Presses a number key on the question block. */
	void pressOption(int number) {
		keyPressed(new KeyEvent(InputConstants.KEY_1 + number - 1, 0, 0));
	}

	boolean requestCardVisibleForTest() { return shownApproval != null || shownQuestion != null; }

	void setComposerForTest(String text) {
		composer.setValue(text);
	}

	String composerValueForTest() {
		return composer.getValue();
	}

	/** The list has no machine filter any more; kept so older self-tests still run. */
	String cycleMachineForTest() { return null; }

	/** Whether the focused thread's row is within the visible window. */
	boolean focusedRowVisibleForTest() {
		List<T3State.ThreadRow> rows = listRows();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i) != null && rows.get(i).id().equals(mod.state().focusedThreadId())) return i >= listScroll && i < listScroll + visibleRows();
		}
		return false;
	}

	int sidebarCountForTest() {
		return (int) listRows().stream().filter(java.util.Objects::nonNull).count();
	}

	/** The same path as the mouse wheel over the list. */
	void scrollSidebarForTest(int notches) {
		for (int i = 0; i < Math.abs(notches); i++) mouseScrolled(listLeft() + 5, height / 2.0, 0, notches > 0 ? -1 : 1);
	}

	/** Same as clicking a thread in the list. */
	void selectThreadForTest(String id) { select(id); }

	void startNewThread() {
		if (!newThread) switchDraft(() -> newThread = true);
	}

	/** There is no model picker on the laptop any more; kept so older self-tests still run. */
	void openPicker() { }

	/** Drives the real composer path. */
	void typeAndSubmit(String text, boolean stayOpen) {
		composer.setValue(text);
		submit(stayOpen);
	}

	// ----- Input -----

	private void submit(boolean stayOpen) {
		String text = composer.getValue().trim();
		if (text.isEmpty()) return;
		String key = draftKey();
		// Nothing was sent (no thread to send to): keep the text and the panel.
		T3CraftClient client = mod;
		if (!mod.send(text, newThread, null, () -> client.restoreFailedSend(key, text))) return;
		composerDraft.sent(mod::saveDraft);
		composer.setValue("");
		newThread = false;
		scroll = 0;
		updateHint();
		if (!stayOpen) onClose();
	}

	private void select(String id) {
		switchDraft(() -> {
			mod.focus(id);
			selectedRequest = null;
			newThread = false;
		});
		scroll = 0;
		updateHint();
		setFocused(composer);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		T3State.Question question = newThread ? null : currentQuestion();
		boolean composerEmpty = composer.getValue().isEmpty();
		if (question != null && composerEmpty && event.key() >= InputConstants.KEY_1 && event.key() <= InputConstants.KEY_9) {
			pickOption(event.key() - InputConstants.KEY_1);
			return true;
		}
		if (question != null && event.isConfirmation()) {
			String text = composer.getValue().trim();
			if (!text.isEmpty() && question.allowCustomAnswer()) {
				composer.setValue("");
				answerQuestion(new com.google.gson.JsonPrimitive(text));
			} else if (text.isEmpty() && question.multiSelect() && !multiPicked.isEmpty()) {
				confirmMulti();
			}
			return true;
		}
		if (event.isConfirmation() && composer.isFocused()) {
			submit(event.hasShiftDown());
			return true;
		}
		// Y/N only when you are not typing in the composer, and never as part of a Ctrl/Alt/Cmd chord.
		boolean chord = event.hasControlDown() || event.hasAltDown() || (event.modifiers() & 0x8) != 0;
		if (composerEmpty && !composer.isFocused() && !chord && (event.key() == InputConstants.KEY_Y || event.key() == InputConstants.KEY_N) && approve.visible) {
			answer(event.key() == InputConstants.KEY_Y ? "accept" : "decline");
			return true;
		}
		if (composerEmpty && mod.openKeyCloses() && mod.openKey().matches(event) && System.currentTimeMillis() - openedAt > 250) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		// Swallow the open key's character (` by default), digits that picked an option, and Y/N that answered an approval.
		if (composer.getValue().isEmpty() && !newThread && currentQuestion() != null && event.codepoint() >= '1' && event.codepoint() <= '9') {
			return true;
		}
		if (composer.getValue().isEmpty() && (typesOpenKey(event.codepoint())
			|| (approve.visible && !composer.isFocused() && (event.codepoint() == 'y' || event.codepoint() == 'n' || event.codepoint() == 'Y' || event.codepoint() == 'N')))) {
			return true;
		}
		return super.charTyped(event);
	}

	/** Whether this character is what the open key types, so pressing it to open or close never lands in the composer. */
	private boolean typesOpenKey(int codepoint) {
		return mod.openKeyCloses() && mod.openKeyName().equalsIgnoreCase(Character.toString(codepoint));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.x() >= listLeft() && event.x() < listRight() && event.y() >= listTop() && event.y() < listTop() + visibleRows() * ROW) {
			List<T3State.ThreadRow> rows = listRows();
			int index = (int) ((event.y() - listTop()) / ROW) + listScroll;
			if (index < rows.size()) {
				if (rows.get(index) == null) { snoozedExpanded = !snoozedExpanded; clampListScroll(listRows().size()); return true; }
				select(rows.get(index).id());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x < listRight() + PAD) {
			listScroll -= listWheel.rows(scrollY * 2);
			clampListScroll(listRows().size());
			return true;
		}
		scroll = Math.max(0, scroll + chatWheel.rows(scrollY * 3));
		return true;
	}

	// ----- Drawing -----

	/** How the answer options sit: rows per column, one or two columns, and how many of them fit on screen. */
	record OptionGrid(int rows, int columns, int shown) {}

	/**
	 * One column while every option fits in {@code height}, else two; past that, as many as fit (always at least
	 * one row). At GUI scale 5–6 nine options never run over the title bar or off the panel.
	 */
	static OptionGrid optionGrid(int count, int height, int rowHeight) {
		if (count <= 0) return new OptionGrid(0, 1, 0);
		int fit = Math.max(1, height / rowHeight);
		if (count <= fit) return new OptionGrid(count, 1, count);
		int rows = Math.min(fit, (count + 1) / 2);
		return new OptionGrid(rows, 2, Math.min(count, rows * 2));
	}

	/** Places the request block (approval or question) above the composer and sets every widget's visibility. */
	private void layout(T3State.Snapshot snapshot) {
		shownFocus = snapshot.focus();
		T3State.ThreadRow row = snapshot.focusedRow();
		boolean online = mod.state().online(snapshot.focusedId());
		int x0 = mainLeft();
		int x1 = mainRight();
		int width = x1 - x0;
		int composerY = composerY();
		int roomTop = mainTop() + 4 * font.lineHeight; // the conversation always keeps a few rows

		shownApproval = newThread ? null : currentApproval();
		shownQuestion = newThread || shownApproval != null ? null : currentQuestion();
		approve.visible = deny.visible = shownApproval != null;
		approve.active = deny.active = online && !mod.approvalInFlight(shownFocus);
		for (Button option : options) option.visible = false;
		stop.visible = row != null && row.status() == T3State.Status.WORKING && online && !newThread;
		send.active = online || newThread;
		approvalDetail = List.of();
		questionText = List.of();

		int top = composerY - 4;
		if (shownApproval != null) {
			top -= BUTTON;
			approve.setY(top);
			deny.setY(top);
			List<FormattedCharSequence> detail = font.split(Component.literal(shownApproval.detail() == null ? "" : shownApproval.detail()), width);
			int roomRows = Math.max(1, (top - 4 - font.lineHeight - roomTop) / font.lineHeight);
			approvalDetail = detail.subList(0, Math.min(detail.size(), Math.min(4, roomRows)));
			top -= 3 + approvalDetail.size() * font.lineHeight + font.lineHeight + 2;
		} else if (shownQuestion != null) {
			int count = Math.min(shownQuestion.options().size(), MAX_OPTIONS);
			// The header and one line of the question always show. Options take two columns before the conversation
			// gives up rows, and the conversation keeps one row before options go (they still answer to 1–9).
			int header = 2 + font.lineHeight + font.lineHeight + 2;
			int limit = roomTop;
			OptionGrid grid = optionGrid(count, top - header - limit, BUTTON + 2);
			if (grid.shown() < count) {
				limit = mainTop() + font.lineHeight;
				grid = optionGrid(count, top - header - limit, BUTTON + 2);
			}
			List<FormattedCharSequence> text = font.split(Component.literal(shownQuestion.question() == null ? "" : shownQuestion.question()), width);
			int optionsHeight = grid.rows() * (BUTTON + 2);
			int roomRows = Math.max(1, (top - optionsHeight - header + font.lineHeight - limit) / font.lineHeight);
			questionText = text.subList(0, Math.min(text.size(), Math.min(3, roomRows)));
			int columnWidth = grid.columns() == 1 ? width : (width - 4) / 2;
			int optionsTop = top - optionsHeight;
			for (int i = 0; i < count; i++) {
				Button option = options.get(i);
				if (i >= grid.shown()) continue; // Still picked with its number key; the hint says so.
				int column = grid.columns() == 1 ? 0 : i / grid.rows();
				int rowIndex = grid.columns() == 1 ? i : i % grid.rows();
				T3State.Option choice = shownQuestion.options().get(i);
				String prefix = (i + 1) + "  " + (shownQuestion.multiSelect() ? (multiPicked.contains(choice.value()) ? "[x] " : "[ ] ") : "");
				option.setMessage(Component.literal(T3Hud.ellipsize(font, prefix + choice.label(), columnWidth - 12)));
				option.setX(column == 0 ? x0 : x1 - columnWidth);
				option.setWidth(columnWidth);
				option.setY(optionsTop + rowIndex * (BUTTON + 2));
				option.visible = true;
				option.active = online;
			}
			top = optionsTop - (2 + questionText.size() * font.lineHeight + font.lineHeight + 2);
		}
		blockTop = top;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		T3State.Snapshot snapshot = mod.state().snapshot();
		layout(snapshot);
		T3Ui.panel(graphics, MARGIN, MARGIN, width - MARGIN, height - MARGIN);
		T3Ui.dividerVertical(graphics, listRight() + PAD / 2, MARGIN + PAD, height - MARGIN - PAD);
		extractList(graphics, snapshot, mouseX, mouseY);
		extractThread(graphics, snapshot);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	private void extractList(GuiGraphicsExtractor graphics, T3State.Snapshot snapshot, int mouseX, int mouseY) {
		int x0 = listLeft();
		int x1 = listRight();
		int y = MARGIN + PAD;
		graphics.text(font, "T3 Code", x0 + 1, y + 1, T3Ui.TITLE, false);
		int connection = snapshot.connected() ? 0xFF3FB950 : snapshot.error() == null ? T3Ui.IDLE : T3Ui.FAILED;
		T3Ui.dot(graphics, x0 + 4 + font.width("T3 Code") + 4, y + 3, connection);

		List<T3State.ThreadRow> rows = listRows();
		clampListScroll(rows.size());
		int visible = visibleRows();
		int top = listTop();
		String focused = mod.state().focusedThreadId();
		y = top;
		for (int i = listScroll; i < rows.size() && i < listScroll + visible; i++) {
			T3State.ThreadRow row = rows.get(i);
			boolean hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + ROW;
			if (row == null) {
				if (hovered) graphics.fill(x0, y, x1, y + ROW, 0x18000000);
				graphics.text(font, (snoozedExpanded ? "▾ " : "▸ ") + "Snoozed", x0 + 3, y + 2, T3Ui.MUTED, false);
				y += ROW;
				continue;
			}
			boolean selected = row.id().equals(focused) && !newThread;
			if (selected) graphics.fill(x0, y, x1, y + ROW, 0x30000000);
			else if (hovered) graphics.fill(x0, y, x1, y + ROW, 0x18000000);
			boolean online = mod.state().online(row.id());
			T3Ui.dot(graphics, x0 + 3, y + 4, T3Ui.statusColor(row.status(), online));
			graphics.text(font, T3Ui.glyph(mod, row), x0 + 11, y + 2, 0xFFFFFFFF, false);
			graphics.text(font, T3Hud.ellipsize(font, row.title(), x1 - x0 - 24), x0 + 22, y + 2, selected ? T3Ui.TITLE : T3Ui.TEXT, false);
			y += ROW;
		}
		if (rows.isEmpty()) {
			graphics.text(font, snapshot.connected() ? "No active threads." : "Connecting…", x0 + 3, top + 2, T3Ui.MUTED, false);
		}
		// Scroll thumb when the list overflows.
		if (rows.size() > visible) {
			int track = visible * ROW;
			int thumb = Math.max(8, track * visible / rows.size());
			int at = top + (track - thumb) * listScroll / Math.max(1, rows.size() - visible);
			graphics.fill(x1 + 1, at, x1 + 3, at + thumb, 0x60000000);
		}
	}

	private void extractThread(GuiGraphicsExtractor graphics, T3State.Snapshot snapshot) {
		int x0 = mainLeft();
		int x1 = mainRight();
		int y = MARGIN + PAD;
		T3State.ThreadRow row = snapshot.focusedRow();
		T3State.Focus focus = snapshot.focus();

		if (!snapshot.connected() && row == null) {
			String message = snapshot.error() == null ? "Connecting to T3 Code…" : snapshot.error();
			graphics.textWithWordWrap(font, Component.literal(message), x0, y + 1, x1 - x0, snapshot.error() == null ? T3Ui.MUTED : T3Ui.FAILED);
			return;
		}
		if (row == null && !newThread) {
			graphics.text(font, snapshot.threads().isEmpty() ? "No threads yet." : "Pick a thread on the left.", x0, y + 1, T3Ui.MUTED, false);
			return;
		}
		if (row == null) row = snapshot.threads().isEmpty() ? null : snapshot.threads().getFirst();
		if (row == null) return;

		// Header: the title, and the status at the right end in its colour.
		boolean online = mod.state().online(row.id());
		String status = newThread ? "" : T3Ui.status(row, online);
		int statusWidth = font.width(status);
		int statusRight = stop.visible ? stop.getX() - 6 : x1;
		graphics.text(font, status, statusRight - statusWidth, y + 1, T3Ui.statusColor(row.status(), online), false);
		String title = newThread ? "New thread" : row.title();
		graphics.text(font, T3Hud.ellipsize(font, title, statusRight - statusWidth - 8 - x0), x0, y + 1, T3Ui.TITLE, false);
		T3Ui.dividerHorizontal(graphics, x0, x1, y + HEADER);

		int top = mainTop();
		int bottom = blockTop - 2;
		String error = mod.state().connectionError(row.id());
		if (error == null) error = snapshot.detailError();
		if (error != null) {
			List<FormattedCharSequence> lines = font.split(Component.literal(error), x1 - x0);
			int errorHeight = Math.min(Math.max(0, bottom - top - 2 * font.lineHeight), lines.size() * font.lineHeight + 2);
			graphics.enableScissor(x0, top, x1, top + errorHeight);
			for (int i = 0; i < lines.size(); i++) graphics.text(font, lines.get(i), x0, top + 1 + i * font.lineHeight, T3Ui.FAILED, false);
			graphics.disableScissor();
			top += errorHeight + 4;
		}

		if (!newThread && focus != null && focus.threadId().equals(row.id()) && bottom > top) {
			int width = x1 - x0 - 2;
			List<T3Markdown.Line> lines = lines(focus, T3Ui.provider(mod, row), width);
			// scroll counts rows up from the bottom, so while reading back, rows streaming in below
			// would push the view; move it by the same amount to keep the same rows on screen.
			if (scroll > 0 && focus.threadId().equals(anchorThread) && width == anchorWidth && lines.size() > anchorCount) {
				scroll += lines.size() - anchorCount;
			}
			anchorThread = focus.threadId();
			anchorWidth = width;
			anchorCount = lines.size();
			scroll = Math.min(scroll, Math.max(0, lines.size() - 1));
			if (lines.isEmpty()) graphics.text(font, row.status() == T3State.Status.WORKING ? "Working…" : "No messages yet.", x0, top + 1, T3Ui.MUTED, false);
			else scroll = T3Ui.drawLines(graphics, font, lines, x0, x1, top, bottom, scroll);
		} else if (newThread) {
			graphics.text(font, "Type the first message below.", x0, top + 1, T3Ui.MUTED, false);
		}

		// The request block, laid out by layout(): the buttons are widgets, the words are drawn here.
		int blockY = blockTop;
		if (shownApproval != null) {
			T3Ui.dividerHorizontal(graphics, x0, x1, blockY - 2);
			graphics.text(font, "Approve " + shownApproval.kind() + "?", x0, blockY + 2, T3Ui.NEEDS_YOU, false);
			for (int i = 0; i < approvalDetail.size(); i++) {
				graphics.text(font, approvalDetail.get(i), x0, blockY + 2 + font.lineHeight + i * font.lineHeight, T3Ui.TEXT, false);
			}
		} else if (shownQuestion != null) {
			T3Ui.dividerHorizontal(graphics, x0, x1, blockY - 2);
			T3State.UserInput input = currentInput();
			String step = input != null && input.questions().size() > 1 ? "  " + (questionIndex + 1) + "/" + input.questions().size() : "";
			graphics.text(font, (shownQuestion.header() == null ? "Question" : shownQuestion.header()) + step, x0, blockY + 2, T3Ui.NEEDS_YOU, false);
			for (int i = 0; i < questionText.size(); i++) {
				graphics.text(font, questionText.get(i), x0, blockY + 2 + font.lineHeight + i * font.lineHeight, T3Ui.TEXT, false);
			}
		}
	}

	/** Rendered conversation rows, rebuilt only when the focus snapshot, agent name or width changes. */
	private List<T3Markdown.Line> lines(T3State.Focus focus, String agent, int width) {
		if (focus == cachedFocus && width == cachedWidth && agent.equals(cachedAgent)) return cachedLines;
		cachedLines = T3Ui.conversation(font, agent, focus, width);
		cachedFocus = focus;
		cachedAgent = agent;
		cachedWidth = width;
		return cachedLines;
	}
}
