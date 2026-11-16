"""The user's big worm (.bbmodel, Blockbench) into the mod: a compact model file the game reads (bones, cubes with their
box-UV faces, the animations), a texture painted on a fresh UV layout, and a copy of the .bbmodel with that layout and
texture for further work in Blockbench.

Colours: one hue for the flesh, only slight variation (in game differences show strongly)."""
import base64
import io
import json
import math
import random
import sys

from PIL import Image

SRC = sys.argv[1]
OUT_MODEL = sys.argv[2]
OUT_TEX = sys.argv[3]
OUT_BB = sys.argv[4]

d = json.load(open(SRC, encoding='utf-8'))
els = {e['uuid']: e for e in d['elements']}
groups = {g['uuid']: g for g in d.get('groups', [])}

# which material each cube is painted in, by the bone it belongs to
MATERIAL = {'segment': 'flesh', 'head start': 'flesh', 'top plate': 'plate', 'bottom plate': 'plate', 'frontmouth': 'lip',
            'frontmouth2': 'lip', 'teeth': 'tooth', 'teeth2': 'tooth', 'sidepieces': 'membrane'}


def size(e):
    return tuple(int(round(e['to'][i] - e['from'][i])) for i in range(3))


# ---- the bones, as the game reads them -------------------------------------------------------------------------
cubes = []  # (element, material)


def node(n, material):
    if isinstance(n, str):
        e = els[n]
        if e.get('type', 'cube') != 'cube':
            return None
        cubes.append((e, material))
        return {'cube': e['uuid']}
    g = groups.get(n['uuid'], n)
    mat = MATERIAL.get(g.get('name'), material)
    return {'name': g.get('name'), 'origin': g.get('origin', [0, 0, 0]), 'rotation': g.get('rotation', [0, 0, 0]),
            'children': [c for c in (node(ch, mat) for ch in n.get('children', [])) if c is not None]}


bones = [b for b in (node(n, 'flesh') for n in d['outliner'] if not isinstance(n, str)) for b in [b] if b is not None]

# ---- a fresh box UV layout: one region per size and material (the same cube turned shares it) --------------------
regions = {}
for e, mat in cubes:
    w, h, dd = size(e)
    regions.setdefault((w, h, dd, mat), (2 * (w + dd), dd + h))
order = sorted(regions, key=lambda k: (-regions[k][1], -regions[k][0]))
WIDTH = 512
places, x, y, shelf = {}, 0, 0, 0
for k in order:
    W, H = regions[k]
    if x + W > WIDTH:
        x, y, shelf = 0, y + shelf, 0
    places[k] = (x, y)
    x, shelf = x + W, max(shelf, H)
HEIGHT = 1
while HEIGHT < y + shelf:
    HEIGHT *= 2


def box_uv(u, v, w, h, dd):
    return {
        'east': [u, v + dd, u + dd, v + dd + h],
        'north': [u + dd, v + dd, u + dd + w, v + dd + h],
        'west': [u + dd + w, v + dd, u + 2 * dd + w, v + dd + h],
        'south': [u + 2 * dd + w, v + dd, u + 2 * dd + 2 * w, v + dd + h],
        'up': [u + dd + w, v + dd, u + dd, v],
        'down': [u + dd + 2 * w, v, u + dd + w, v + dd],
    }


# ---- the texture: one hue each, varied only slightly --------------------------------------------------------------
PAL = {
    'flesh': (27, 23, 27),  # dark as thalan, a breath of red
    'plate': (21, 19, 22),
    'lip': (34, 20, 23),
    'membrane': (38, 21, 25),
    'tooth': (116, 108, 96),
}
tex = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0))
px = tex.load()


def cluster(seed, x, y, cell=2):
    return random.Random(seed * 1000003 + (x // cell) * 7919 + (y // cell) * 104729).random()


def paint(x0, y0, w, h, mat, side, seed):
    base = PAL[mat]
    for yy in range(h):
        for xx in range(w):
            n = cluster(seed, xx, yy) - 0.5  # -0.5 .. 0.5
            if mat == 'tooth':
                t = yy / max(1, h - 1)  # darker at the root
                k = -14 + 18 * t + 5 * n
            else:
                k = 5 * n  # a few shades either way, no more
                if side in ('east', 'west', 'up', 'down'):  # the skin round the body: soft creases across it
                    if (xx + int(3 * cluster(seed + 7, 0, yy // 6, 1))) % 9 == 0:
                        k -= 4
                if cluster(seed + 3, xx, yy, 3) < 0.03:
                    k += 5  # a rare paler fleck
            c = tuple(max(0, min(255, int(round(ch + k)))) for ch in base)
            px[x0 + xx, y0 + yy] = c + (255,)


for i, (k, (W, H)) in enumerate(sorted(regions.items(), key=lambda kv: str(kv[0]))):
    u, v = places[k]
    w, h, dd, mat = k
    for side, (a, b, c, e2) in box_uv(u, v, w, h, dd).items():
        x0, y0, x1, y1 = min(a, c), min(b, e2), max(a, c), max(b, e2)
        if x1 > x0 and y1 > y0:
            paint(x0, y0, x1 - x0, y1 - y0, mat, side, i * 17 + len(side))
tex.save(OUT_TEX)

# ---- the model file for the game --------------------------------------------------------------------------------
out_cubes = {}
for e, mat in cubes:
    w, h, dd = size(e)
    u, v = places[(w, h, dd, mat)]
    out_cubes[e['uuid']] = {'from': e['from'], 'to': e['to'], 'origin': e.get('origin', [0, 0, 0]), 'rotation': e.get('rotation', [0, 0, 0]),
                            'faces': box_uv(u, v, w, h, dd)}


def resolve(b):
    if 'cube' in b:
        return out_cubes[b['cube']]
    return {'name': b['name'], 'origin': b['origin'], 'rotation': b['rotation'], 'children': [resolve(c) for c in b['children']]}


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
json.dump({'texture': [WIDTH, HEIGHT], 'bones': [resolve(b) for b in bones], 'animations': animations},
          open(OUT_MODEL, 'w', encoding='utf-8'), separators=(',', ':'))

# ---- the .bbmodel again, with the layout and the texture (to go on with in Blockbench) ---------------------------
for e, mat in cubes:
    w, h, dd = size(e)
    u, v = places[(w, h, dd, mat)]
    e['uv_offset'] = [u, v]
    e['box_uv'] = True
    e['faces'] = {k: {'uv': uv, 'texture': 0} for k, uv in box_uv(u, v, w, h, dd).items()}
buf = io.BytesIO()
tex.save(buf, 'PNG')
d['resolution'] = {'width': WIDTH, 'height': HEIGHT}
d['textures'] = [{'path': '', 'name': 'big_worm.png', 'folder': 'entity', 'namespace': '', 'id': '0', 'width': WIDTH, 'height': HEIGHT,
                  'uv_width': WIDTH, 'uv_height': HEIGHT, 'particle': False, 'render_mode': 'default', 'render_sides': 'auto',
                  'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False, 'visible': True,
                  'internal': True, 'saved': False, 'uuid': '5a0c3b1e-0000-4000-8000-00000000b16w'.replace('b16w', 'b160'),
                  'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}]
json.dump(d, open(OUT_BB, 'w', encoding='utf-8'))
print('cubes', len(cubes), 'regions', len(regions), 'texture', WIDTH, 'x', HEIGHT, 'animations', list(animations))
