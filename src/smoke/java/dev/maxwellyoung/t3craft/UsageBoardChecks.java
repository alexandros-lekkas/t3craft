package dev.maxwellyoung.t3craft;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fixture checks for the normalized T3 snapshot and standalone Muse allowance shape. */
public final class UsageBoardChecks {
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
	private static UsageBoardData read(String name) throws Exception {
		try (var in = UsageBoardChecks.class.getResourceAsStream("/usage-board/" + name)) {
			if (in == null) throw new AssertionError("missing fixture " + name);
			return UsageBoardData.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		}
	}
	public static void main(String[] args) throws Exception {
		var live = read("live.json");
		var codexParsed = live.providers().stream().filter(p -> p.name().equals("codex")).findFirst().orElseThrow();
		check(codexParsed.windowPercent() == 43 && codexParsed.resetsAt().toString().equals("2026-11-13T03:07:14Z"), "Codex weekly percent/reset parsed");
		check(codexParsed.tokensToday() != null && codexParsed.tokensToday() > 0, "today tokens summed from token components");
		check(live.providers().stream().anyMatch(p -> p.name().equals("claudeAgent") && p.windowPercent() == 76), "Claude window parsed");
		check(live.providers().stream().anyMatch(p -> p.name().equals("opencode") && p.windowPercent() == null && p.state() == UsageBoardData.State.UNKNOWN), "unsupported provider remains unknown");
		var museData = read("muse.json");
		check(museData.providers().size() == 1, "Muse allowance parsed");
		var allowance = museData.providers().get(0);
		check(allowance.name().equals("Muse Code Power Usage") && allowance.windowPercent() == 90, "Muse tier and window parsed");
		check(allowance.resetsAt().toEpochMilli() == 1793953152000L && allowance.state() == UsageBoardData.State.NEAR_LIMIT, "Muse reset and near-limit state parsed");
		var contract=UsageBoardData.fromServerConfig(JsonParser.parseString("""
		 {"providers":[
		  {"driver":"claudeAgent","usageLimits":{"checkedAt":"2026-01-01T00:00:00Z","windows":[{"kind":"session","label":"5-hour","usedPercent":34,"resetsAt":"2026-01-01T05:00:00Z"},{"kind":"weekly","label":"Weekly","usedPercent":22,"resetsAt":"2026-01-07T00:00:00Z"}]}},
		  {"driver":"codex","usageLimits":{"windows":[{"kind":"weekly","label":"Weekly","usedPercent":67}]}},
		  {"driver":"muse","usageLimits":{"unavailable":{"reason":"unsupported"},"windows":[]}}
		 ]}
		 """).getAsJsonObject());
		var claudeConfig=named(contract,"Claude Agent");check(claudeConfig.windowPercent()==34&&claudeConfig.secondaryPercent()==22&&"5-HOUR".equals(claudeConfig.primaryLabel()),"Claude session and weekly windows mapped under T3's labels");
		var codexConfig=named(contract,"Codex");check(codexConfig.windowPercent()==67&&codexConfig.secondaryPercent()==null&&codexConfig.secondaryLabel()==null,"Codex weekly-only window mapped as primary");
		check(named(contract,"Muse").state()==UsageBoardData.State.UNKNOWN&&named(contract,"Muse").note().equals("T3 TRACKS NO LIMITS FOR IT"),"unsupported provider has unknown usage and says so");
		check(contract.providers().getFirst().name().equals("Codex"),"fullest provider first");
		// The shape the real T3 serves today (src/smoke/resources/usage-board/server-config.json): a monthly-only Codex,
		// two Claude accounts with two weekly windows each and no session, and mostly disabled providers.
		UsageBoardData real;
		try(var in=UsageBoardChecks.class.getResourceAsStream("/usage-board/server-config.json")){real=UsageBoardData.fromServerConfig(JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject());}
		check(real.providers().stream().map(UsageBoardData.Provider::name).toList().equals(java.util.List.of("Codex","Claude","Claude Personal","Claude Team","Muse Code")),"enabled providers only, fullest first, second Claude account named apart: "+real.providers().stream().map(UsageBoardData.Provider::name).toList());
		var codexReal=named(real,"Codex");
		check(codexReal.windowPercent()==90&&"MONTHLY".equals(codexReal.primaryLabel())&&codexReal.state()==UsageBoardData.State.NEAR_LIMIT&&codexReal.resetsAt().toString().equals("2026-12-06T14:24:27Z"),"Codex's monthly window is shown and near its limit");
		var claudeReal=named(real,"Claude");
		check(claudeReal.windowPercent()==33&&"WEEKLY".equals(claudeReal.primaryLabel())&&claudeReal.secondaryPercent()==45&&"WEEKLY · FABLE".equals(claudeReal.secondaryLabel()),"Claude's two weekly windows, labelled as T3 labels them: "+claudeReal);
		check(named(real,"Claude Team").windowPercent()==0&&named(real,"Claude Team").resetsAt()==null&&"SESSION".equals(named(real,"Claude Team").primaryLabel()),"a session with no reset time");
		check(named(real,"Muse Code").state()==UsageBoardData.State.UNKNOWN&&"NO USAGE DATA".equals(named(real,"Muse Code").note()),"a provider without usageLimits says it has no usage data");
		check(real.providers().stream().noneMatch(p->p.name().equals("Opencode")||p.name().equals("Cursor")),"disabled providers are left off the board");
		var none=UsageBoardData.fromServerConfig(JsonParser.parseString("{\"providers\":[{\"driver\":\"cursor\",\"enabled\":false}]}").getAsJsonObject());
		check(none.providers().isEmpty()&&"NO PROVIDERS".equals(none.title()),"nothing enabled says so");
		check(UsageBoardData.failed(T3McpBackend.LEGACY_REQUIRED).detail().equals("THIS PAIRING CANNOT READ LIMITS")&&UsageBoardData.failed(null).providers().isEmpty(),"a failed read shows no numbers and a short reason");
		Path out=Path.of("build/usage-board");Files.createDirectories(out);
		long now=System.currentTimeMillis();
		var claude=new UsageBoardData.Provider("CLAUDE",34,java.time.Instant.ofEpochMilli(now+171*60_000L),3_140_000L,UsageBoardData.State.NORMAL,22,java.time.Instant.ofEpochMilli(now+3*86_400_000L),java.util.List.of(new UsageBoardData.Agent("AUTH-REFACTOR",1_210_000),new UsageBoardData.Agent("DOCS SWEEP",640_000)));
		var codex=new UsageBoardData.Provider("CODEX",18,java.time.Instant.ofEpochMilli(now+245*60_000L),1_420_000L,UsageBoardData.State.NORMAL,47,java.time.Instant.ofEpochMilli(now+4*86_400_000L),java.util.List.of());
		var muse=new UsageBoardData.Provider("MUSE",27,java.time.Instant.ofEpochMilli(now+562*60_000L),820_000L,UsageBoardData.State.NORMAL,12,java.time.Instant.ofEpochMilli(now+19*86_400_000L),java.util.List.of());
		write(out.resolve("normal.png"),new UsageBoardData(java.util.List.of(claude,codex,muse)),now);
		write(out.resolve("near.png"),new UsageBoardData(java.util.List.of(new UsageBoardData.Provider("CLAUDE",86,claude.resetsAt(),claude.tokensToday(),UsageBoardData.State.NEAR_LIMIT,22,claude.secondaryReset(),java.util.List.of()),codex,muse)),now);
		write(out.resolve("limited.png"),new UsageBoardData(java.util.List.of(new UsageBoardData.Provider("CLAUDE",100,java.time.Instant.ofEpochMilli(now+102*60_000L),claude.tokensToday(),UsageBoardData.State.LIMITED,64,claude.secondaryReset(),java.util.List.of()),codex,muse)),now);
		write(out.resolve("unknown.png"),new UsageBoardData(java.util.List.of(claude,codex,new UsageBoardData.Provider("MUSE",null,null,null,UsageBoardData.State.UNKNOWN))),now);
		write(out.resolve("connect.png"),null,now);
		write(out.resolve("real.png"),real,now);
		write(out.resolve("reading.png"),UsageBoardData.READING,now);
		write(out.resolve("failed.png"),UsageBoardData.failed("Connection refused"),now);
		checkWall(now);
		System.out.println("Usage board fixtures parsed successfully.");
	}
	/**
	 * The board on the office wall, for every turn of the office and every template: 18 maps on one wall face,
	 * inside the building, facing into the room from solid template blocks, with the room side free of blocks and
	 * props, in reading order as seen from the room, and the image split into exactly its tiles.
	 */
	private static void checkWall(long now) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		int columns=UsageBoardLayout.COLUMNS,rows=UsageBoardLayout.ROWS;
		check(columns*128==UsageBoardRenderer.WIDTH&&rows*128==UsageBoardRenderer.HEIGHT,"the anchor is the renderer's 6 x 3 maps");
		var origin=new net.minecraft.core.BlockPos(-40,70,12);
		// Props that stand in a cell (feet within it), so a frame there would clip them.
		var props=new java.util.HashSet<net.minecraft.core.BlockPos>();
		for(var e:OfficeProps.PROPS){var p=e.getAsJsonObject().getAsJsonArray("pos");var at=net.minecraft.core.BlockPos.containing(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble());props.add(at);props.add(at.above());}
		// Wall-mounted props sit on a block face (x or z a whole number): keep them off the board's wall face too.
		var anchor=UsageBoardLayout.ANCHOR.getAsJsonArray("topLeft");double face=anchor.get(0).getAsInt()+1;int z0=anchor.get(2).getAsInt(),top=anchor.get(1).getAsInt();
		for(var e:OfficeProps.PROPS){var p=e.getAsJsonObject().getAsJsonArray("pos");double x=p.get(0).getAsDouble(),y=p.get(1).getAsDouble(),z=p.get(2).getAsDouble();
			check(!(Math.abs(x-face)<.25&&z>z0-.25&&z<z0+UsageBoardLayout.COLUMNS+.25&&y>top-UsageBoardLayout.ROWS+.75&&y<top+1.25),"no prop hangs on the board's wall face: "+e.getAsJsonObject().get("id").getAsString());}
		for(int floors:new int[]{1,2,3}){
			var blocks=new java.util.HashMap<net.minecraft.core.BlockPos,String>();
			for(var e:OfficeDesign.template(floors).getAsJsonArray("blocks")){var b=e.getAsJsonObject();var p=b.getAsJsonArray("pos");blocks.put(new net.minecraft.core.BlockPos(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt()),b.get("state").getAsString());}
			for(var rotation:net.minecraft.world.level.block.Rotation.values()){
				var frame=new OfficeFrame(origin,rotation);String where=rotation+", "+floors+" floor(s)";
				var tiles=UsageBoardLayout.tiles(frame);
				check(tiles.size()==columns*rows,"every map hung ("+where+")");
				check(tiles.stream().map(UsageBoardLayout.Tile::pos).distinct().count()==tiles.size(),"one frame per cell ("+where+")");
				var box=OfficePlacement.bounds(frame,floors);
				var facing=frame.direction(UsageBoardLayout.FACING);
				var right=frame.direction(UsageBoardLayout.FACING.getCounterClockWise());
				var first=tiles.getFirst().pos();
				for(var t:tiles){
					String what="map "+t.column()+","+t.row()+" ("+where+")";
					check(t.facing()==facing&&facing.getAxis().isHorizontal(),"every frame faces the same way: "+what);
					// Coplanar: every frame sits in the same plane across the facing axis, at its column and row of the grid.
					check(t.pos().equals(first.relative(right,t.column()).below(t.row())),"on one wall face in reading order: "+what);
					check(box.contains(net.minecraft.world.phys.Vec3.atCenterOf(t.pos())),"inside the building: "+what);
					var cell=frame.local(t.pos());var wall=frame.local(t.wall());
					check(cell.getY()>=0&&cell.getY()<T3Office.FLOOR_HEIGHT-1,"on the ground floor: "+what);
					check(cell.getX()>=0&&cell.getX()<30&&cell.getZ()>=0&&cell.getZ()<24,"the frame hangs in the room: "+what+" at "+cell);
					check(cell.equals(wall.relative(UsageBoardLayout.FACING)),"faces into the room, away from its wall: "+what);
					check(!blocks.containsKey(cell)&&!props.contains(cell),"nothing stands in front of the board: "+what+" ("+blocks.get(cell)+")");
					String state=blocks.get(wall);
					check(state!=null,"backed by a template block: "+what+" at "+wall);
					var parsed=net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK,new com.mojang.brigadier.StringReader(state),false).blockState();
					var turned=frame.state(parsed);
					check(turned.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO)&&turned.canOcclude()&&!state.contains("glass"),"backed by a solid block: "+what+" is "+state);
					check(frame.block(wall.getX(),wall.getY(),wall.getZ()).equals(t.wall()),"wall block round-trips through the frame: "+what);
				}
			}
		}
		var ids=new java.util.HashSet<Integer>();for(int i=0;i<columns*rows;i++)check(ids.add(UsageBoardWall.mapId(i).id())&&UsageBoardWall.mapId(i).id()>=UsageBoardWall.FIRST_MAP,"client-only map ids are distinct and high");
		// The tiles put back together are the board, pixel for pixel.
		byte[] board=UsageBoardRenderer.renderMap(null,now),joined=new byte[board.length];
		for(int r=0;r<rows;r++)for(int c=0;c<columns;c++){byte[] tile=UsageBoardLayout.tileColours(board,c,r);for(int y=0;y<128;y++)System.arraycopy(tile,y*128,joined,(r*128+y)*UsageBoardRenderer.WIDTH+c*128,128);}
		check(java.util.Arrays.equals(board,joined),"the six by three tiles cover the board exactly");
		check(java.util.stream.IntStream.range(0,board.length).map(i->board[i]&0xff).distinct().count()>2&&java.util.stream.IntStream.range(0,board.length).allMatch(i->(board[i]&0xff)>=4),"unpaired board renders its connect-T3 screen in real map colours");
		System.out.println("Usage board wall: "+columns+"x"+rows+" maps, every turn and template, solid backing, facing the room");
	}
	private static UsageBoardData.Provider named(UsageBoardData data,String name){return data.providers().stream().filter(p->p.name().equals(name)).findFirst().orElseThrow(()->new AssertionError("no provider "+name+" in "+data.providers().stream().map(UsageBoardData.Provider::name).toList()));}
	private static void write(Path path, UsageBoardData data,long now) throws Exception {
		int[] pixels=UsageBoardRenderer.render(data,now);
		check(pixels.length==768*384 && java.util.Arrays.stream(pixels).distinct().count()>3,"render has nonblank 768x384 pixels");
		BufferedImage image=new BufferedImage(768,384,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,768,384,pixels,0,768);
		ImageIO.write(image,"png",path.toFile());
		check(UsageBoardRenderer.renderMap(data,now).length==768*384,"map palette bytes produced");
	}
}
