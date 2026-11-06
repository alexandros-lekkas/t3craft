"""Read-only visual fixture: twelve threads, mixed activity, no real agent calls."""
import importlib.util,json
from pathlib import Path
spec=importlib.util.spec_from_file_location('fixture',Path(__file__).parent.parent/'feature-fixture.py')
f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
s=f.Fixture(25720,'A',False)
s.threads={}
providers=['claudeAgent','codex','muse','grok','cursor','opencode','pi','kiro','claudeAgent','codex','muse','grok']
for i,p in enumerate(providers):
 ident=f'office-{i}';t=f.thread(ident,waiting=i==4)
 t['title']=['Build the studio','Review the API','Ship the release','Run the checks','Choose a direction','Polish the UI','Document the work','Ready for review','Design the landing page','Fix the renderer','Test the build','Tidy the docs'][i]
 t['driverId']=p;t['modelSelection']['instanceId']=p
 if i<4 or i>=8:t['latestTurn']['state']='running';t['messages']=[];t['activities']=[]
 s.threads[ident]=t
p=Path('run/config/office-fixture.json');p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(json.dumps({'environments':[{'label':'Studio Mac','baseUrl':'http://127.0.0.1:25720','accessToken':'fixture-only'}],'threadId':'office-0','books':False}));p.chmod(0o600)
s.serve_forever()
