package dev.maxwellyoung.t3craft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A long loft with one floor per paired machine, and where the agent
 * villagers go inside it. Pure layout (no client or server classes) so the client village and the
 * shared server office both use it.
 *
 * Coordinates are relative to the office origin (inside floor corner of the ground floor). A floor
 * runs +z from the kitchen/entry wall (z = -1) to the big window (z = LENGTH); facing the window,
 * +x is the left (plain) wall and -x the right (shelf) wall. Floor n sits FLOOR_HEIGHT * n above.
 */
final class T3Office {
	static final int WIDTH = 30;
	static final int LENGTH = 24;
	static final int HEIGHT = 5;
	static final int FLOOR_HEIGHT = HEIGHT + 1;

	/**
	 * Where a villager ends up: cell, how far it sinks in (seats), and which way it faces
	 * (NaN = toward the player).
	 */
	record Spot(int x, int z, double sink, float yaw) {
		boolean seat() {
			return sink > 0;
		}
	}

 static final Spot[] WORK = OfficeDesign.spots("work");
 static final Spot[] NEEDS_YOU = OfficeDesign.spots("needsYou");
 static final Spot[] LOUNGE = OfficeDesign.spots("lounge");
 static final Spot FRIDGE = OfficeDesign.spot(OfficeDesign.floor().getAsJsonObject("fridge"));
 static final int[] DOOR = {25, 0};
 static final int[] LADDER = {28, 12};
 private static final boolean[][] BLOCKED = OfficeDesign.blocked();
 static boolean[][] blocked() { return BLOCKED; }

	/**
	 * Shortest walk between floor cells (8-way, no corner cutting), as cell centres. The destination
	 * may be a seat. Falls back to a straight line if there's no path.
	 */
	static List<double[]> path(int fromX, int fromZ, int toX, int toZ) {
		boolean[][] b = blocked();
		fromX = clamp(fromX, WIDTH); fromZ = clamp(fromZ, LENGTH);
		int[][] previous = new int[WIDTH * LENGTH][];
		boolean[] seen = new boolean[WIDTH * LENGTH];
		ArrayDeque<int[]> open = new ArrayDeque<>();
		open.add(new int[] {fromX, fromZ});
		seen[fromX * LENGTH + fromZ] = true;
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
		boolean found = false;
		while (!open.isEmpty()) {
			int[] cell = open.poll();
			if (cell[0] == toX && cell[1] == toZ) {
				found = true;
				break;
			}
			for (int[] s : steps) {
				int x = cell[0] + s[0], z = cell[1] + s[1];
				if (x < 0 || z < 0 || x >= WIDTH || z >= LENGTH || seen[x * LENGTH + z]) continue;
				boolean target = x == toX && z == toZ;
				if (b[x][z] && !target) continue;
				// Diagonals only between two open cells, so nobody clips a table corner.
				if (s[0] != 0 && s[1] != 0 && (b[cell[0] + s[0]][cell[1]] || b[cell[0]][cell[1] + s[1]])) continue;
				seen[x * LENGTH + z] = true;
				previous[x * LENGTH + z] = cell;
				open.add(new int[] {x, z});
			}
		}
		List<double[]> route = new ArrayList<>();
		if (!found) {
			route.add(new double[] {toX + 0.5, toZ + 0.5});
			return route;
		}
		for (int[] cell = {toX, toZ}; cell != null && !(cell[0] == fromX && cell[1] == fromZ); cell = previous[cell[0] * LENGTH + cell[1]]) {
			route.addFirst(new double[] {cell[0] + 0.5, cell[1] + 0.5});
		}
		return route;
	}

	private static int clamp(int v, int size) {
		return Math.max(0, Math.min(size - 1, v));
	}

	/** Commands for the whole building, one floor per label (bottom up), relative to the origin. */
 static List<String> blueprint(List<String> floors) {
  var commands=new ArrayList<>(OfficeDesign.blueprint(Math.max(2,floors.size()*2)));
  for(int floor=0;floor<Math.max(2,floors.size()*2);floor++)commands.add(sign(29,floor*6+2,13,List.of("FLOOR "+(floor+1),floors.isEmpty()?"T3 STUDIO":wrap(floors.get(floor/2),15,1).stream().findFirst().orElse(""))).replace("facing=east","facing=west"));
  commands.add(sign(27,2,-2,List.of("T3 OFFICE","SCANDI STUDIO")).replace("facing=east","facing=north"));return commands;
 }
 static List<String> lamps(int floors, boolean night) { return OfficeDesign.lamps(Math.max(2,floors*2),night); }

	/** One thing written on the whiteboard: who needs you and what for. */
	record Note(String title, String detail) {}

	/**
	 * What's written on a floor's whiteboard: the first waiting thread and what it's asking
	 * (the command or question), on two birch signs above the villagers waiting there.
	 * An empty list wipes the board.
	 */
	static List<String> whiteboard(int floor, List<Note> notes) {
		List<String> c = new ArrayList<>();
		int y = floor * FLOOR_HEIGHT + 2;
		if (notes.isEmpty()) {
			c.add("setblock 1 " + y + " 14 birch_wall_sign[facing=east]");
			c.add("setblock 1 " + y + " 16 birch_wall_sign[facing=east]");
			return c;
		}
		Note first = notes.getFirst();
		List<String> left = new ArrayList<>();
		left.add(notes.size() > 1 ? "NEEDS YOU (" + notes.size() + ")" : "NEEDS YOU");
		left.addAll(wrap(first.title(), 15, 3));
		String detail = first.detail() == null || first.detail().isBlank() ? "Open the panel to answer" : first.detail();
		List<String> right = new ArrayList<>(wrap(detail, 15, 4));
		// Facing the board from the room, z 28 is on the left and z 27 on the right.
		c.add(sign(1, y, 16, left));
		c.add(sign(1, y, 14, right));
		return c;
	}

	private static String sign(int x, int y, int z, List<String> lines) {
		List<String> padded = new ArrayList<>(lines);
		while (padded.size() < 4) padded.add("");
		StringBuilder messages = new StringBuilder();
		for (String line : padded.subList(0, 4)) messages.append(messages.isEmpty() ? "" : ",").append('"').append(line).append('"');
		return "setblock " + x + " " + y + " " + z + " birch_wall_sign[facing=east]{front_text:{messages:[" + messages + "]}}";
	}

	static List<String> wrap(String text, int width, int maxLines) {
		// Sign text goes inside a quoted SNBT string: keep it to plain printable characters.
		String clean = text.replaceAll("[^A-Za-z0-9 .,:;!?&()+'/=_<>-]", "").trim();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : clean.split("\\s+")) {
			while (word.length() > width) {
				if (!line.isEmpty()) {
					lines.add(line.toString());
					line.setLength(0);
				}
				lines.add(word.substring(0, width));
				word = word.substring(width);
				if (lines.size() >= maxLines) return lines.subList(0, maxLines);
			}
			if (line.length() + word.length() + (line.isEmpty() ? 0 : 1) > width) {
				lines.add(line.toString());
				line.setLength(0);
				if (lines.size() >= maxLines) return lines.subList(0, maxLines);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
		}
		if (!line.isEmpty() && lines.size() < maxLines) lines.add(line.toString());
		return lines;
	}

	/** Rewrites "fill x y z …" / "setblock x y z …" relative coordinates to absolute ones. */
	static String absolute(String command, int ox, int oy, int oz) {
		int coords = command.startsWith("fill") ? 6 : 3;
		String[] parts = command.split(" ", coords + 2);
		for (int i = 0; i < coords; i++) {
			int base = switch (i % 3) {
				case 0 -> ox;
				case 1 -> oy;
				default -> oz;
			};
			parts[i + 1] = Integer.toString(base + Integer.parseInt(parts[i + 1]));
		}
		return String.join(" ", parts);
	}
}
