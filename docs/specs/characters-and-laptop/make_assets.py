from pathlib import Path
import json,struct,zlib
root=Path('.')
pix=[[(39,43,53,255) for x in range(32)] for y in range(32)]
def rect(x0,y0,x1,y1,c):
 for y in range(y0,y1):
  for x in range(x0,x1):pix[y][x]=(*c,255)
rect(0,0,16,8,(17,23,33));rect(1,1,15,2,(83,160,193))
for y,w in [(3,8),(5,11),(6,5)]:rect(2,y,2+w,y+1,(122,215,185))
rect(0,8,16,16,(63,71,83))
for y in [9,11,13]:
 for x in range(1,15,3):rect(x,y,x+2,y+1,(164,172,182))
for x,y,c in [(18,2,(92,104,119)),(22,2,(24,29,38)),(26,2,(197,204,211)),(18,6,(89,214,173)),(22,6,(85,99,115)),(26,6,(232,171,117))]:rect(x,y,x+4,y+4,c)
raw=b''.join(b'\0'+bytes(v for p in row for v in p) for row in pix)
def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
(root/'laptop.png').write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',32,32,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
sw={'case':[9.25,1.25,10.25,2.25],'dark':[11.25,1.25,12.25,2.25],'silver':[13.25,1.25,14.25,2.25],'light':[9.25,3.25,10.25,4.25],'trackpad':[11.25,3.25,12.25,4.25]}
def cube(a,b,uv,rotation=None,faces=None):
 e={'from':a,'to':b,'faces':{d:{'uv':sw.get(uv,uv),'texture':'#palette'} for d in ['north','south','east','west','up','down']}}
 if faces:
  for d,u in faces.items():e['faces'][d]['uv']=u
 if rotation:e['rotation']=rotation
 return e
rot={'origin':[8,1,4],'axis':'x','angle':-22.5,'rescale':False}
model={'credit':'T3 Craft draft, 26.3 cuboid model','ambientocclusion':False,'textures':{'palette':'t3craft:item/laptop','particle':'t3craft:item/laptop'},'elements':[
 cube([2,0,4],[14,1,13],'case'),cube([2,1,3.5],[14,9,4.5],'dark',rot),
 cube([2.75,1.8,4.5],[13.25,8.2,4.55],'dark',rot,{'south':[0,0,8,4]}),
 cube([3,1,5],[13,1.06,9.5],'dark',faces={'up':[0,4,8,8]}),
 cube([6,1,10],[10,1.06,12],'trackpad'),cube([12.5,1,11.5],[13,1.08,12],'light')],
 'display':{'gui':{'rotation':[25,205,0],'translation':[0,1,0],'scale':[1,1,1]},'ground':{'translation':[0,2,0],'scale':[.6,.6,.6]},'fixed':{'rotation':[0,0,0],'translation':[0,0,0],'scale':[1,1,1]},'firstperson_righthand':{'rotation':[0,-20,0],'translation':[1,1,-2],'scale':[.7,.7,.7]},'firstperson_lefthand':{'rotation':[0,20,0],'translation':[1,1,-2],'scale':[.7,.7,.7]},'thirdperson_righthand':{'rotation':[75,0,0],'translation':[0,2,1],'scale':[.5,.5,.5]},'thirdperson_lefthand':{'rotation':[75,0,0],'translation':[0,2,1],'scale':[.5,.5,.5]}}}
(root/'laptop.model.json').write_text(json.dumps(model,indent=2)+'\n')
(root/'laptop.item.json').write_text(json.dumps({'model':{'type':'minecraft:model','model':'t3craft:item/laptop'}},indent=2)+'\n')
recipe={'type':'minecraft:crafting_shaped','category':'misc','pattern':['GGG','IRI','III'],'key':{'G':'minecraft:glass_pane','I':'minecraft:iron_ingot','R':'minecraft:redstone'},'result':{'id':'minecraft:paper','count':1,'components':{'minecraft:item_model':'t3craft:laptop','minecraft:custom_name':{'text':'T3 Laptop'},'minecraft:custom_data':{'t3craft_laptop':1}}}}
(root/'laptop.recipe.json').write_text(json.dumps(recipe,indent=2)+'\n')
print('Wrote 32x32 RGBA texture, six-element model, item definition and carrier recipe.')
