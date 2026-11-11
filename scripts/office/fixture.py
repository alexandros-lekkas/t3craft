"""Read-only visual fixture: twelve threads, mixed activity, no real agent calls."""
import argparse,importlib.util,json
from pathlib import Path
spec=importlib.util.spec_from_file_location('fixture',Path(__file__).parent.parent/'feature-fixture.py')
f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
ap=argparse.ArgumentParser()
ap.add_argument('--port',type=int,default=25720,help='0 picks a free port, so several checkouts can run the office fixture at once.')
ap.add_argument('--port-file',type=Path,help='Write the chosen port here once it listens.')
ap.add_argument('--config',type=Path,default=Path('run/config/office-fixture.json'),help='Pairing file the dev client reads (-Pconfig).')
args=ap.parse_args()
s=f.Fixture(args.port,'A',False)
s.threads={}
providers=['claudeAgent','codex','muse','grok','cursor','opencode','pi','kiro','claudeAgent','codex','muse','grok']
for i,p in enumerate(providers):
 ident=f'office-{i}';t=f.thread(ident,waiting=i==4)
 t['title']=['Build the studio','Review the API','Ship the release','Run the checks','Choose a direction','Polish the UI','Document the work','Ready for review','Design the landing page','Fix the renderer','Test the build','Tidy the docs'][i]
 t['driverId']=p;t['modelSelection']['instanceId']=p
 if i<4 or i>=8:t['latestTurn']['state']='running';t['messages']=[];t['activities']=[]
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
 """/_qa/delegate?parent=office-0&title=… spawns a subagent of that thread; /_qa/finish?id=… completes it (agents talking, see AgentTalk)."""
 q=f.parse_qs(f.urlparse(self.path).query)
 if self.path.startswith('/_qa/delegate?'):
  with self.server.lock:
   parent=q['parent'][0];ident=parent+'-helper-'+str(len(self.server.threads))
   t=f.thread(ident);t['title']=q.get('title',['Take the tests'])[0];t['messages']=[];t['activities']=[]
   t['lineage']={'parentThreadId':parent,'relationshipToParent':'subagent','sideChat':False}
   t['latestTurn']={'turnId':ident+'-turn','state':'running','requestedAt':f.datetime.now(f.timezone.utc).isoformat()}
   self.server.threads[ident]=t;broadcast(self.server,t)
   return self.reply({'id':ident})
 if self.path.startswith('/_qa/finish?'):
  with self.server.lock:
   t=self.server.threads[q['id'][0]];t['latestTurn']['state']='completed';t['updatedAt']=f.datetime.now(f.timezone.utc).isoformat()
   broadcast(self.server,t)
   return self.reply({'ok':True})
 return stock_get(self)
f.Handler.do_GET=do_GET
p=args.config;p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(json.dumps({'environments':[{'label':'Studio Mac','baseUrl':f'http://127.0.0.1:{s.server_port}','accessToken':'fixture-only'}],'threadId':'office-0','books':False}));p.chmod(0o600)
if args.port_file:args.port_file.parent.mkdir(parents=True,exist_ok=True);args.port_file.write_text(str(s.server_port))
print('Office fixture ready:',s.server_port,flush=True)
s.serve_forever()
