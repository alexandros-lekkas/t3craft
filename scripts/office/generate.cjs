// Design A expanded to a 30 x 24 interior. Insert usable space rather than scaling avatars.
const fs=require('fs'),g=require('./scandi.cjs');
const out='src/main/resources/data/t3craft/office';fs.mkdirSync(out,{recursive:true});
const X=x=>x+Math.max(0,Math.min(4,Math.floor(x)-3))+(x>=10?2:0)+(x>=17?2:0);
const Z=z=>z+(z>=8?4:0)+(z>=14?2:0);
function anchor(a,key=''){
 if(Array.isArray(a)){if(['desk','monitor','block','left','middle','right','firstStep','top','outside','sign','door','board','path'].includes(key)&&typeof a[0]==='number')return a.length===2?[X(a[0]),Z(a[1])]:a.map((v,i)=>i%3===0?X(v):i%3===2?Z(v):v);return a.map(v=>anchor(v));}
 if(a&&typeof a==='object')return Object.fromEntries(Object.entries(a).map(([k,v])=>[k,k==='x'?X(v):k==='z'?Z(v):anchor(v,k)]));return a;
}
for(let n=1;n<=2;n++){
 const d=g.build(n),blocks=new Map();
 const placements=JSON.parse(fs.readFileSync(__dirname+'/props-source.json'));
 for(let floor=0;floor<n;floor++)for(const edit of placements.blockEdits){let [x,y,z]=edit.pos;let key=[x,y+floor*6,z].join(',');if(edit.to==='air')d.blocks.delete(key);else d.blocks.set(key,edit.to);}
 for(const a of d.anchors.floors)for(const w of a.work){d.blocks.delete([w.monitor[0],a.y+1,w.monitor[2]].join(','));d.blocks.set([w.x,a.y,w.z].join(','),'light_gray_carpet');}
 for(const [p,state] of d.blocks){const[x,y,z]=p.split(',').map(Number);
 const stretch=/(planks|concrete|glass_pane|smooth_quartz|carpet|bookshelf|stripped_birch|iron_block|pale_oak_planks|polished_andesite_stairs|birch_slab|moss_block)/.test(state);
 for(let xx=X(x);xx<(stretch?X(x+1):X(x)+1);xx++)for(let zz=Z(z);zz<(stretch?Z(z+1):Z(z)+1);zz++)blocks.set([xx,y,zz].join(','),state);
 }
 fs.writeFileSync(`${out}/scandi-${n}.json`,JSON.stringify({blocks:[...blocks].map(([p,state])=>({pos:p.split(',').map(Number),state})),anchors:anchor(d.anchors),lamps:anchor(d.lamps)}));
}
// Same mapping for the original prop placement sheet.
const source=__dirname+'/props-source.json';if(fs.existsSync(source)){
 let p=JSON.parse(fs.readFileSync(source));let props=[...p.desks.flatMap(d=>d.props.map(prop=>({...prop,desk:d.slot}))),...(p.room||[])];
 // Desk kits and shared room furniture use exactly the same expanded frame.
 props=props.map(p=>({...p,pos:[X(p.pos[0]),p.pos[1],Z(p.pos[2])]}));
 fs.writeFileSync(`${out}/props.json`,JSON.stringify({props}));
}
