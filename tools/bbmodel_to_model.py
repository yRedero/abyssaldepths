"""A Blockbench model (.bbmodel, modded entity) into the mod as it is: the compact model file the game reads (bones,
cubes with the model's own face UVs, the animations) and its embedded texture (or textures: the first is the model's,
the rest are saved beside it as variants, name_1.png, name_2.png ...). Unlike bbmodel_to_entity.py (the worm), the
UVs and the texture are kept as made in Blockbench.

    python tools/bbmodel_to_model.py <in.bbmodel> <models/entity/x.json> <textures/entity/x.png>
"""
import base64
import io
import json
import sys

from PIL import Image

SRC, OUT_MODEL, OUT_TEX = sys.argv[1], sys.argv[2], sys.argv[3]
d = json.load(open(SRC, encoding='utf-8'))
els = {e['uuid']: e for e in d['elements']}
groups = {g['uuid']: g for g in d.get('groups', [])}
res = d.get('resolution', {'width': 64, 'height': 64})
W, H = int(res['width']), int(res['height'])


def cube(e):
    faces = {}
    for side, f in e.get('faces', {}).items():
        if f.get('texture') is None or 'uv' not in f:
            continue
        faces[side] = [float(v) for v in f['uv']]
    return {'from': e['from'], 'to': e['to'], 'origin': e.get('origin', [0, 0, 0]), 'rotation': e.get('rotation', [0, 0, 0]),
            'inflate': e.get('inflate', 0), 'faces': faces}


def node(n):
    if isinstance(n, str):
        e = els[n]
        return cube(e) if e.get('type', 'cube') == 'cube' else None
    g = groups.get(n['uuid'], n)
    return {'name': g.get('name'), 'origin': g.get('origin', [0, 0, 0]), 'rotation': g.get('rotation', [0, 0, 0]),
            'children': [c for c in (node(ch) for ch in n.get('children', [])) if c is not None]}


# loose cubes at the top (outside any group) go into a root bone
top = [node(n) for n in d['outliner']]
bones = [b for b in top if b is not None and 'name' in b]
loose = [b for b in top if b is not None and 'name' not in b]
if loose:
    bones.append({'name': 'root', 'origin': [0, 0, 0], 'rotation': [0, 0, 0], 'children': loose})

animations = {}
for a in d.get('animations', []):
    anim = {'length': a.get('length', 0), 'loop': a.get('loop', 'once'), 'bones': {}}
    for an in a.get('animators', {}).values():
        chans = {}
        for kf in an.get('keyframes', []):
            p = kf['data_points'][0]
            chans.setdefault(kf['channel'], []).append([kf['time'], float(p['x']), float(p['y']), float(p['z'])])
        for c in chans.values():
            c.sort()
        if chans:
            anim['bones'][an['name']] = chans
    animations[a['name']] = anim
json.dump({'texture': [W, H], 'bones': bones, 'animations': animations}, open(OUT_MODEL, 'w', encoding='utf-8'), separators=(',', ':'))

for i, t in enumerate(d.get('textures', [])):
    src = t.get('source', '')
    if not src.startswith('data:image'):
        continue
    im = Image.open(io.BytesIO(base64.b64decode(src.split(',', 1)[1]))).convert('RGBA')
    out = OUT_TEX if i == 0 else OUT_TEX[:-4] + '_' + str(i) + '.png'
    im.save(out)
print('bones', len(bones), 'animations', list(animations), 'texture', W, 'x', H)
