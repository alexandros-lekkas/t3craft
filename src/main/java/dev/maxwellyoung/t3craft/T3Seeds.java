package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The curated places a T3 World can start in: verified Minecraft 26.3 seeds with a recorded office
 * site. Bundled as {@code t3craft/seeds.json} on the classpath (not a datapack, so packs can't change
 * it) and read on both sides.
 */
final class T3Seeds {
	/** Where the office goes in this seed: its origin (inside floor corner) and the way its view side faces. */
	record Site(BlockPos origin, Direction facing) {}

	record Seed(String id, String name, long seed, String blurb, Site site) {}

	private static List<Seed> seeds;

	/** Cycle order; the first entry is the default. */
	static synchronized List<Seed> all() {
		if (seeds != null) return seeds;
		List<Seed> list = new ArrayList<>();
		try (InputStream in = T3Seeds.class.getResourceAsStream("/t3craft/seeds.json")) {
			if (in == null) throw new IllegalStateException("t3craft/seeds.json is missing");
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray entries = root.getAsJsonArray("seeds");
			for (JsonElement element : entries) list.add(parse(element.getAsJsonObject()));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Could not read t3craft/seeds.json", e);
		}
		seeds = List.copyOf(list);
		return seeds;
	}

	static Seed parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.get("name").getAsString();
		long seed = Long.parseLong(json.get("seed").getAsString().trim());
		String blurb = json.has("blurb") ? json.get("blurb").getAsString() : "";
		Site site = null;
		if (json.has("office") && json.get("office").isJsonObject()) {
			JsonObject office = json.getAsJsonObject("office");
			Direction facing = Direction.byName(office.get("facing").getAsString());
			if (facing == null || facing.getAxis().isVertical()) throw new IllegalStateException("Seed " + id + ": facing must be north, south, east or west");
			site = new Site(new BlockPos(office.get("x").getAsInt(), office.get("y").getAsInt(), office.get("z").getAsInt()), facing);
		}
		return new Seed(id, name, seed, blurb, site);
	}

	static Seed byId(String id) {
		if (id == null) return null;
		for (Seed seed : all()) if (seed.id().equals(id)) return seed;
		return null;
	}

	static Seed defaultSeed() {
		return all().isEmpty() ? null : all().getFirst();
	}
}
