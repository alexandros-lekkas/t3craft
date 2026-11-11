package dev.maxwellyoung.t3craft;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Pixel-art port of the approved board.js layout and fill-rectangle font. */
public final class UsageBoardRenderer {
 public static final int WIDTH=768,HEIGHT=384;
 private static final int[] PIX=new int[WIDTH*HEIGHT];
 private static final Map<Character,String[]> FONT=new HashMap<>();
 private static final java.awt.image.BufferedImage ICON_ATLAS=loadAtlas();
 private static final int BG=0x191919,CARD=0x353535,INK=0xFFFCF5,MUTED=0xABABAB,DIM=0x6C6C6C,OK=0x7FA796,WARN=0xFAEE4D,RED=0xDC0000,RED_CARD=0x5C191D,TRACK=0x191919,LINE=0x414141;
 private static final int[] BASE={0,0x7FB238,0xF7E9A3,0xC7C7C7,0xFF0000,0xA0A0FF,0xA7A7A7,0x007C00,0xFFFFFF,0xA4A8B8,0x976D4D,0x707070,0x4040FF,0x8F7748,0xFFFCF5,0xD87F33,0xB24CD8,0x6699D8,0xE5E533,0x7FCC19,0xF27FA5,0x4C4C4C,0x999999,0x4C7F99,0x7F3FB2,0x334CB2,0x664C33,0x667F33,0x993333,0x191919,0xFAEE4D,0x5CDBD5,0x4A80FF,0x00D93A,0x815631,0x700200,0xD1B1A1,0x9F5224,0x95576C,0x706C8A,0xBA8524,0x677535,0xA04D4E,0x392923,0x876B62,0x575C5C,0x7A4958,0x4C3E5C,0x4C3223,0x4C522A,0x8E3C2E,0x251610,0xBD3031,0x943F61,0x5C191D,0x167E86,0x3A8E8C,0x562C3E,0x14B485,0x646464,0xD8AF93,0x7FA796};
 private UsageBoardRenderer(){}
 private static java.awt.image.BufferedImage loadAtlas(){try{return javax.imageio.ImageIO.read(UsageBoardRenderer.class.getResourceAsStream("/assets/t3craft/textures/font/provider_icons.png"));}catch(Exception e){return null;}}
 static {
  FONT.put('A',new String[]{".###.","#...#","#...#","#####","#...#","#...#","#...#"});
  FONT.put('B',new String[]{"####.","#...#","#...#","####.","#...#","#...#","####."});
  FONT.put('C',new String[]{".###.","#...#","#....","#....","#....","#...#",".###."});
  FONT.put('D',new String[]{"####.","#...#","#...#","#...#","#...#","#...#","####."});
  FONT.put('E',new String[]{"#####","#....","#....","####.","#....","#....","#####"});
  FONT.put('F',new String[]{"#####","#....","#....","####.","#....","#....","#...."});
  FONT.put('G',new String[]{".###.","#...#","#....","#.###","#...#","#...#",".###."});
  FONT.put('H',new String[]{"#...#","#...#","#...#","#####","#...#","#...#","#...#"});
  FONT.put('I',new String[]{"###",".#.",".#.",".#.",".#.",".#.","###"});
  FONT.put('J',new String[]{"..###","...#.","...#.","...#.","#..#.","#..#.",".##.."});
  FONT.put('K',new String[]{"#...#","#..#.","#.#..","##...","#.#..","#..#.","#...#"});
  FONT.put('L',new String[]{"#....","#....","#....","#....","#....","#....","#####"});
  FONT.put('M',new String[]{"#...#","##.##","#.#.#","#.#.#","#...#","#...#","#...#"});
  FONT.put('N',new String[]{"#...#","#...#","##..#","#.#.#","#..##","#...#","#...#"});
  FONT.put('O',new String[]{".###.","#...#","#...#","#...#","#...#","#...#",".###."});
  FONT.put('P',new String[]{"####.","#...#","#...#","####.","#....","#....","#...."});
  FONT.put('Q',new String[]{".###.","#...#","#...#","#...#","#.#.#","#..#.",".##.#"});
  FONT.put('R',new String[]{"####.","#...#","#...#","####.","#.#..","#..#.","#...#"});
  FONT.put('S',new String[]{".####","#....","#....",".###.","....#","....#","####."});
  FONT.put('T',new String[]{"#####","..#..","..#..","..#..","..#..","..#..","..#.."});
  FONT.put('U',new String[]{"#...#","#...#","#...#","#...#","#...#","#...#",".###."});
  FONT.put('V',new String[]{"#...#","#...#","#...#","#...#","#...#",".#.#.","..#.."});
  FONT.put('W',new String[]{"#...#","#...#","#...#","#.#.#","#.#.#","##.##","#...#"});
  FONT.put('X',new String[]{"#...#","#...#",".#.#.","..#..",".#.#.","#...#","#...#"});
  FONT.put('Y',new String[]{"#...#","#...#",".#.#.","..#..","..#..","..#..","..#.."});
  FONT.put('Z',new String[]{"#####","....#","...#.","..#..",".#...","#....","#####"});
  FONT.put('0',new String[]{".###.","#...#","#..##","#.#.#","##..#","#...#",".###."});
  FONT.put('1',new String[]{".#.","##.",".#.",".#.",".#.",".#.","###"});
  FONT.put('2',new String[]{".###.","#...#","....#","...#.","..#..",".#...","#####"});
  FONT.put('3',new String[]{".###.","#...#","....#","..##.","....#","#...#",".###."});
  FONT.put('4',new String[]{"...#.","..##.",".#.#.","#..#.","#####","...#.","...#."});
  FONT.put('5',new String[]{"#####","#....","####.","....#","....#","#...#",".###."});
  FONT.put('6',new String[]{"..##.",".#...","#....","####.","#...#","#...#",".###."});
  FONT.put('7',new String[]{"#####","....#","...#.","..#..",".#...",".#...",".#..."});
  FONT.put('8',new String[]{".###.","#...#","#...#",".###.","#...#","#...#",".###."});
  FONT.put('9',new String[]{".###.","#...#","#...#",".####","....#","...#.",".##.."});
  FONT.put('%',new String[]{"##..#","##..#","...#.","..#..",".#...","#..##","#..##"});
  FONT.put(':',new String[]{".","#",".",".",".","#","."});
  FONT.put('.',new String[]{".",".",".",".",".",".","#"});
  FONT.put(',',new String[]{".",".",".",".",".","#","#"});
  FONT.put('-',new String[]{"....","....","....","####","....","....","...."});
  FONT.put('/',new String[]{"....#","...#.","...#.","..#..",".#...",".#...","#...."});
  FONT.put('·',new String[]{".",".",".","#",".",".","."});
  FONT.put('+',new String[]{".....","..#..","..#..","#####","..#..","..#..","....."});
  FONT.put('—',new String[]{"#####",".....","....."});
  FONT.put(' ',new String[]{"..","..","..","..","..","..",".."});
  FONT.put('—',new String[]{".....",".....",".....","#####",".....",".....","....."});
 }
 private static void rect(int x,int y,int w,int h,int c){int argb=0xff000000|c;for(int yy=Math.max(0,y);yy<Math.min(HEIGHT,y+h);yy++)Arrays.fill(PIX,yy*WIDTH+Math.max(0,x),yy*WIDTH+Math.max(0,x)+Math.max(0,Math.min(WIDTH,x+w)-Math.max(0,x)),argb);}
 private static void icon(String name,int x,int y,int size){int idx=name.toLowerCase().contains("codex")?0:name.toLowerCase().contains("claude")?1:name.toLowerCase().contains("muse")?3:11;rect(x-2,y-2,size+4,size+4,accent(name));if(ICON_ATLAS==null)return;int sx=(idx%12)*16,sy=(idx/12)*16;for(int yy=0;yy<size;yy++)for(int xx=0;xx<size;xx++){int c=ICON_ATLAS.getRGB(sx+xx*16/size,sy+yy*16/size);if((c>>>24)>80)rect(x+xx,y+yy,1,1,c&0xffffff);}}
 private static int width(String s,int sc){int n=0;for(char c:s.toUpperCase().toCharArray())n+=(glyph(c)[0].length()+1)*sc;return Math.max(0,n-sc);}
 private static String[] glyph(char c){return FONT.getOrDefault(c,FONT.getOrDefault(Character.toUpperCase(c),new String[]{"#####","....#","...#.","..#..",".#...",".....","..#.."}));}
 private static void text(String s,int x,int y,int scale,int color){for(char c:s.toUpperCase().toCharArray()){String[] rows=glyph(c);for(int j=0;j<rows.length;j++)for(int i=0;i<rows[j].length();i++)if(rows[j].charAt(i)=='#')rect(x+i*scale,y+j*scale,scale,scale,color);x+=(rows[0].length()+1)*scale;}}
 private static void box(int x,int y,int w,int h,int color){rect(x+3,y,w-6,h,color);rect(x,y+3,w,h-6,color);}
 public static int[] render(UsageBoardData data,long now){Arrays.fill(PIX,0xff000000|BG);text("USAGE LIMITS",16,14,3,INK);List<UsageBoardData.Provider> ps=data==null?List.of():data.providers().stream().limit(3).toList();
  String status="NO DATA";int statusColor=MUTED;if(!ps.isEmpty()){boolean limited=ps.stream().anyMatch(p->p.state()==UsageBoardData.State.LIMITED),near=ps.stream().anyMatch(p->p.state()==UsageBoardData.State.NEAR_LIMIT),known=ps.stream().anyMatch(p->p.windowPercent()!=null);status=limited?"LIMITED":near?"NEAR LIMIT":known?"ALL CLEAR":"NO DATA";statusColor=limited||!known?INK:near?WARN:OK;}
  String clock="UPDATED  "+java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(now));int cw=width(clock,2),pw=width(status,2)+20;box(WIDTH-16-cw-16-pw,10,pw,26,status.equals("LIMITED")?RED:status.equals("NEAR LIMIT")?0x624613:0x3D4040);text(status,WIDTH-16-cw-16-10-width(status,2),16,2,statusColor);text(clock,WIDTH-16-cw,18,2,DIM);
  int more=data==null?0:data.providers().size()-ps.size();if(more>0)text("+"+more+" MORE",16+width("USAGE LIMITS",3)+14,20,2,DIM);
  if(ps.isEmpty()){box(8,48,752,328,0x343434);if(data==null){text("CONNECT T3",30,112,4,INK);text("NO USAGE DATA YET",30,160,2,MUTED);}else{text(fit(data.title()==null?"USAGE UNAVAILABLE":data.title(),4,700),30,112,4,INK);text(fit(data.detail()==null?"T3 HAS NO LIMITS TO SHOW YET":data.detail(),2,700),30,160,2,MUTED);}}else{int col=WIDTH/ps.size();for(int i=0;i<ps.size();i++)draw(ps.get(i),8+i*col+0,48,col-16,328,now);}
  return Arrays.copyOf(PIX,PIX.length);
 }
 /** {@code s} cut to {@code max} pixels at {@code scale}, ending in "." when cut. */
 private static String fit(String s,int scale,int max){String t=s.toUpperCase(Locale.ROOT);if(width(t,scale)<=max)return t;while(t.length()>1&&width(t+".",scale)>max)t=t.substring(0,t.length()-1);return t.stripTrailing()+".";}
 /** Words of {@code s} on lines no wider than {@code max} pixels. */
 private static List<String> wrap(String s,int scale,int max){List<String> lines=new ArrayList<>();String line="";for(String word:s.toUpperCase(Locale.ROOT).split(" ")){String next=line.isEmpty()?word:line+" "+word;if(!line.isEmpty()&&width(next,scale)>max){lines.add(line);line=word;}else line=next;}if(!line.isEmpty())lines.add(fit(line,scale,max));return lines;}
 private static void draw(UsageBoardData.Provider p,int x,int y,int w,int h,long now){int v=p.windowPercent()==null?0:p.windowPercent();int color=p.windowPercent()==null?DIM:v>=100?RED:v>=80?WARN:OK;boolean unknown=p.windowPercent()==null;box(x,y,w,h,p.state()==UsageBoardData.State.LIMITED?RED_CARD:unknown?0x343434:CARD);rect(x+3,y,w-6,4,accent(p.name()));icon(p.name(),x+12,y+16,24);
  // The name at the big size when it fits beside the icon, else smaller, else cut.
  int nameRoom=w-52-10;if(width(p.name(),3)<=nameRoom)text(p.name(),x+52,y+18,3,INK);else text(fit(p.name(),2,nameRoom),x+52,y+22,2,INK);
  boolean nearTag=p.state()==UsageBoardData.State.NEAR_LIMIT,limitedTag=p.state()==UsageBoardData.State.LIMITED;int tagW=nearTag?width("NEAR LIMIT",2)+12:limitedTag?width("LIMITED",2)+12:0;
  text(fit(p.primaryLabel()==null?"SESSION":p.primaryLabel(),2,w-24-tagW),x+12,y+56,2,MUTED);if(nearTag)text("NEAR LIMIT",x+w-12-width("NEAR LIMIT",2),y+56,2,WARN);if(limitedTag)text("LIMITED",x+w-12-width("LIMITED",2),y+56,2,INK);
  if(unknown){text("--",x+12,y+80,7,DIM);int yy=y+138;for(String line:wrap(p.note()==null?"NO USAGE DATA":p.note(),2,w-24)){text(line,x+12,yy,2,MUTED);yy+=20;}return;}
  if(limitedTag&&v>=100){text(reset(p.resetsAt(),now),x+12,y+92,4,INK);}else{text(String.valueOf(v),x+12,y+80,7,nearTag&&v>=80?WARN:INK);text("%",x+12+width(String.valueOf(v),7)+4,y+101,4,MUTED);if(p.resetsAt()!=null){text("RESETS",x+w-100,y+88,2,DIM);text(reset(p.resetsAt(),now),x+w-100,y+110,2,INK);}}
  bar(x+12,y+138,w-24,16,p.windowPercent(),color);
  if(p.secondaryPercent()!=null||p.secondaryLabel()!=null){String pct=p.secondaryPercent()==null?"--%":p.secondaryPercent()+"%";int pctW=width(pct,3);rect(x+12,y+182,w-24,2,LINE);text(fit(p.secondaryLabel()==null?"WEEKLY":p.secondaryLabel(),2,w-24-pctW-10),x+12,y+194,2,MUTED);text(pct,x+w-12-pctW,y+190,3,p.secondaryPercent()==null?DIM:INK);int sv=p.secondaryPercent()==null?0:p.secondaryPercent();bar(x+12,y+216,w-24,8,p.secondaryPercent(),p.secondaryPercent()==null?DIM:sv>=100?RED:sv>=80?WARN:OK);if(p.secondaryReset()!=null)text("RESETS "+reset(p.secondaryReset(),now),x+12,y+236,2,DIM);}
  // Token counts need T3's diagnostics scope, which the mod's pairing never asks for: no empty "--" row without them.
  if(p.tokensToday()==null&&p.topAgents().isEmpty())return;
  rect(x+12,y+256,w-24,2,LINE);text("TODAY",x+12,y+268,2,MUTED);String tok=fmt(p.tokensToday());text("TOKENS",x+w-12-width("TOKENS",2)-width(tok,2)-8,y+268,2,DIM);text(tok,x+w-12-width(tok,2),y+268,2,INK);
  for(int k=0;k<Math.min(2,p.topAgents().size());k++){UsageBoardData.Agent a=p.topAgents().get(k);int yy=y+290+k*20;rect(x+12,yy,8,8,accent(p.name()));String title=a.title().toUpperCase(Locale.ROOT);int max=w-24-16-width(fmt(a.tokens()),2)-8;while(width(title,2)>max&&title.length()>1)title=title.substring(0,title.length()-1);if(!title.equals(a.title().toUpperCase(Locale.ROOT)))title=title.substring(0,Math.max(0,title.length()-1))+".";text(title,x+24,yy,2,INK);String at=fmt(a.tokens());text(at,x+w-12-width(at,2),yy,2,MUTED);}
 }
 private static void bar(int x,int y,int w,int h,Integer val,int color){rect(x,y,w,h,TRACK);if(val==null){for(int i=0;i<w;i+=8)rect(x+i,y+h/2-1,4,2,LINE);}else{rect(x,y,(int)Math.round(w*Math.min(100,val)/100.0),h,color);rect(x+(int)(w*.8),y,2,h,LINE);}}
 private static int accent(String name){String n=name.toLowerCase();return n.contains("claude")?0xBA6D2C:n.contains("codex")?0x119B72:n.contains("muse")?0x4A80FF:0x5CDBD5;}
 private static String reset(Instant at,long now){if(at==null)return "--";long m=Math.max(0,Duration.between(Instant.ofEpochMilli(now),at).toMinutes());return m>=24*60?(m/1440)+"D "+(m%1440/60)+"H":(m/60)+"H "+String.format(Locale.ROOT,"%02dM",m%60);}
 private static String fmt(Long n){return n==null?"--":n>=1_000_000?String.format(Locale.ROOT,"%.1fM",n/1e6):n>=1000?Math.round(n/1000.0)+"K":n.toString();}
 /** Exact map palette bytes (base MapColor id * 4 + shade). */
 public static byte[] renderMap(UsageBoardData data,long now){int[] argb=render(data,now);byte[] out=new byte[argb.length];Map<Integer,Byte> nearest=new HashMap<>();for(int i=0;i<out.length;i++){out[i]=nearest.computeIfAbsent(argb[i]&0xffffff,UsageBoardRenderer::nearest);}return out;}
 /** The closest map palette byte to one colour; the board uses a handful of colours, so renderMap memoizes this. */
 private static byte nearest(int rgb){int best=0;long distance=Long.MAX_VALUE;for(int id=1;id<BASE.length;id++)for(int shade=0;shade<4;shade++){int b=BASE[id],mul=new int[]{180,220,255,135}[shade];int r=((b>>16)&255)*mul/255,g=((b>>8)&255)*mul/255,bl=(b&255)*mul/255;int rr=(rgb>>16)&255,gg=(rgb>>8)&255,bb=rgb&255;long d=2L*(rr-r)*(rr-r)+4L*(gg-g)*(gg-g)+3L*(bb-bl)*(bb-bl);if(d<distance){distance=d;best=id*4+shade;}}return (byte)best;}
}
