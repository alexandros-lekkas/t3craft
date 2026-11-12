"""Read-only visual fixture: twelve threads, mixed activity, no real agent calls."""
import argparse,datetime,importlib.util,json
from pathlib import Path
spec=importlib.util.spec_from_file_location('fixture',Path(__file__).parent.parent/'feature-fixture.py')
f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
parser=argparse.ArgumentParser()
parser.add_argument('--port',type=int,default=25720,help='0 picks a free port')
parser.add_argument('--port-file',type=Path,help='Write the chosen port here once the fixture listens.')
parser.add_argument('--config',type=Path,default=Path('run/config/office-fixture.json'),help='Pairing file to write for -Pconfig.')
parser.add_argument('--mcp-port',type=int,help='mcpPort to put in the pairing file, so the dev client stays off 25590.')
parser.add_argument('--demo',action='store_true',help='Launch-video staging (scripts/demo/record.sh): believable thread titles, and an approval that is asked for on cue (/_qa/ask) and resumes work when approved.')
args=parser.parse_args()
s=f.Fixture(args.port,'A',False)
s.threads={}
# server.getConfig answers with the providers and usage limits in the real T3's shape, for the usage board.
s.config={'providers':json.loads((Path(__file__).parent.parent.parent/'src/smoke/resources/usage-board/server-config.json').read_text())['providers']}
providers=['claudeAgent','codex','muse','grok','cursor','opencode','pi','kiro','claudeAgent','codex','muse','grok']
# The launch video: the agent that asks is a name people know, and the titles read like real work.
if args.demo:providers=['claudeAgent','codex','muse','grok','claudeAgent','codex','opencode','pi','kiro','cursor','muse','grok']
demo_titles=['Fix the flaky login test','Why is CI red','Bump the dependencies','Migrate off moment.js','Fix the build','Center the div','Write the README','Remove console.logs','Make the tests pass','Add dark mode','Rename utils2.ts','Explain this regex']
demo_done={5:'Centered it. The parent was missing a flex.',6:'README is written. Setup is three steps now.',7:'Removed 47 console.log calls. All checks pass.',10:'Renamed it to dates.ts. 14 imports updated.',11:'It matches an ISO date. Added a comment.'}
# Desks go to working threads first, newest first: these seven fill the ground floor (bench row A: four at work, the third of
# them the thread that will ask; row B: three more) and the eighth desk is the finished thread the player types to, which rests on the sofa.
demo_order=[0,1,4,2,3,8,9,5,6,7,10,11];demo_working={0,1,2,3,4,8,9}
asks={}
for i,p in enumerate(providers):
 ident=f'office-{i}';t=f.thread(ident,waiting=i==4)
 t['title']=['Build the studio','Review the API','Ship the release','Run the checks','Choose a direction','Polish the UI','Document the work','Ready for review','Design the landing page','Fix the renderer','Test the build','Tidy the docs'][i]
 t['driverId']=p;t['modelSelection']['instanceId']=p
 if (i in demo_working and i!=4 if args.demo else i<4 or i>=8):t['latestTurn']['state']='running';t['messages']=[];t['activities']=[]
 if args.demo:
  stamp=(f.datetime.now(f.timezone.utc)-datetime.timedelta(seconds=demo_order.index(i))).isoformat();t['updatedAt']=stamp;t['latestTurn']['requestedAt']=stamp
  t['title']=demo_titles[i];t['messages']=t['messages'][:1]
  if i in demo_done:t['messages'][0]['text']=demo_done[i]
  if i==4:
   # Working at its desk until /_qa/ask: then it needs the player, with this request and message.
   request=t['activities'][0];request['payload']['detail']='rm -rf node_modules'
   asks[ident]={'activities':[request],'messages':[{'id':ident+'-reply','role':'assistant','text':'The lockfile is out of sync. I want a clean install.','streaming':False}]}
   t['hasPendingApprovals']=False;t['activities']=[];t['messages']=[];t['latestTurn']['state']='running'
 s.threads[ident]=t
def dispatch(self,command):
 """A prompt from the game shows up as the player's message and the thread starts working (the stock fixture only overwrites a reply)."""
 self.server.dispatches.append(command)
 t=self.server.threads[command['threadId']]
 if command['type'] in ('thread.turn.start','message.dispatch'):
  text=(command.get('message') or {}).get('text') or command.get('text') or ''
  t['messages'].append({'id':f"{command['threadId']}-user-{len(t['messages'])}",'role':'user','text':text,'streaming':False})
  t['latestTurn']={'turnId':command['threadId']+'-turn-'+str(len(t['messages'])),'state':'running','requestedAt':f.datetime.now(f.timezone.utc).isoformat()}
 else:
  t['activities'].append({'kind':'approval.resolved' if command.get('decision') else 'user-input.resolved','payload':{'requestId':command['requestId']}})
  t['hasPendingApprovals']=t['hasPendingUserInput']=False
  if args.demo:
   t['latestTurn']['state']='running';t['updatedAt']=f.datetime.now(f.timezone.utc).isoformat()
   t['messages'].append({'id':f"{command['threadId']}-reply-{len(t['messages'])}",'role':'assistant','text':'Reinstalling now.','streaming':False})
 for handler,ident in list(self.server.subscribers.items()):
  try: handler.frame({'_tag':'Chunk','requestId':ident,'values':[{'kind':'thread-upserted','thread':t}]})
  except OSError: pass
f.Handler.dispatch=dispatch
def broadcast(server,t):
 for handler,ident in list(server.subscribers.items()):
  try: handler.frame({'_tag':'Chunk','requestId':ident,'values':[{'kind':'thread-upserted','thread':t}]})
  except OSError: pass
stock_get=f.Handler.do_GET
def do_GET(self):
 """/_qa/delegate?parent=office-0&title=…[&driver=…] spawns a subagent of that thread; /_qa/finish?id=… completes it (agents talking, see AgentTalk); /_qa/ask?id=… makes a --demo thread ask for its approval now; /_qa/approve?id=… and /_qa/work?id=… answer it or set a thread working without the game (to record one shot on its own)."""
 q=f.parse_qs(f.urlparse(self.path).query)
 if self.path.startswith('/_qa/delegate?'):
  with self.server.lock:
   parent=q['parent'][0];ident=parent+'-helper-'+str(len(self.server.threads))
   t=f.thread(ident);t['title']=q.get('title',['Take the tests'])[0];t['messages']=[];t['activities']=[]
   if 'driver' in q:t['driverId']=q['driver'][0];t['modelSelection']['instanceId']=q['driver'][0]
   t['lineage']={'parentThreadId':parent,'relationshipToParent':'subagent','sideChat':False}
   t['latestTurn']={'turnId':ident+'-turn','state':'running','requestedAt':f.datetime.now(f.timezone.utc).isoformat()}
   self.server.threads[ident]=t;broadcast(self.server,t)
   return self.reply({'id':ident})
 if self.path.startswith('/_qa/ask?'):
  with self.server.lock:
   t=self.server.threads[q['id'][0]];now=f.datetime.now(f.timezone.utc).isoformat()
   t.update(asks[t['id']]);t['activities'][0]['createdAt']=now;t['hasPendingApprovals']=True;t['updatedAt']=now
   broadcast(self.server,t)
   return self.reply({'ok':True})
 if self.path.startswith('/_qa/approve?') or self.path.startswith('/_qa/work?'):
  with self.server.lock:
   t=self.server.threads[q['id'][0]];t['hasPendingApprovals']=t['hasPendingUserInput']=False;t['latestTurn']['state']='running';t['updatedAt']=f.datetime.now(f.timezone.utc).isoformat()
   if self.path.startswith('/_qa/approve?'):t['activities'].append({'kind':'approval.resolved','payload':{'requestId':'shared-request'}})
   broadcast(self.server,t)
   return self.reply({'ok':True})
 if self.path.startswith('/_qa/finish?'):
  with self.server.lock:
   t=self.server.threads[q['id'][0]];t['latestTurn']['state']='completed';t['updatedAt']=f.datetime.now(f.timezone.utc).isoformat()
   broadcast(self.server,t)
   return self.reply({'ok':True})
 return stock_get(self)
f.Handler.do_GET=do_GET
p=args.config;p.parent.mkdir(parents=True,exist_ok=True)
config={'environments':[{'label':'Studio Mac','baseUrl':f'http://127.0.0.1:{s.server_port}','accessToken':'fixture-only'}],'threadId':'office-0','books':False}
if args.mcp_port:config['mcpPort']=args.mcp_port
p.write_text(json.dumps(config));p.chmod(0o600)
if args.port_file:args.port_file.parent.mkdir(parents=True,exist_ok=True);args.port_file.write_text(str(s.server_port))
print('Office fixture ready:',s.server_port,flush=True)
s.serve_forever()
