"""First versions of the Shadeborn's gestures, written as Blockbench animations into the user's model (to be refined in
Blockbench): into the .bbmodel (a copy, for the user) and into the game's model file.

    python tools/shadeborn_gestures.py <in.bbmodel> <out.bbmodel>
    (then: python tools/bbmodel_to_model.py <out.bbmodel> <models/entity/shadeborn.json> <textures/entity/shadeborn.png>)

Bones of the model: leg1, leg2, body (arm1, arm2, head under it). Rotations in degrees, as the model faces (front towards -z): x positive swings an arm or leg forward and up, leans the body
and the head back (so a nod or bow is negative).
Gestures: nod (Tolerated), wave (Welcome), bow (Kin, Keeper), beckon, point, warn (off with you). (Sitting is the user's
own sitting_relaxed.)
"""
import json
import sys
import uuid

SRC, OUT = sys.argv[1], sys.argv[2]
d = json.load(open(SRC, encoding='utf-8'))
groups = {g['name']: g['uuid'] for g in d.get('groups', [])}


def kf(channel, t, x, y=0, z=0):
    return {'channel': channel, 'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}], 'uuid': str(uuid.uuid4()), 'time': t,
            'color': -1, 'interpolation': 'catmullrom'}


def anim(name, length, loop, bones):
    animators = {}
    for bone, frames in bones.items():
        animators[groups[bone]] = {'name': bone, 'type': 'bone', 'keyframes': frames}
    return {'uuid': str(uuid.uuid4()), 'name': name, 'loop': loop, 'override': False, 'length': length, 'snapping': 24, 'selected': False,
            'anim_time_update': '', 'blend_weight': '', 'start_delay': '', 'loop_delay': '', 'animators': animators}


R = 'rotation'
GESTURES = [
    anim('nod', 0.9, 'once', {'head': [kf(R, 0, 0), kf(R, 0.2, -18), kf(R, 0.4, 0), kf(R, 0.6, -14), kf(R, 0.9, 0)]}),
    anim('wave', 1.4, 'once', {'arm1': [kf(R, 0, 0), kf(R, 0.25, 150, 0, 0), kf(R, 0.5, 150, 0, -25), kf(R, 0.75, 150, 0, 20),
                                        kf(R, 1.0, 150, 0, -25), kf(R, 1.4, 0)]}),
    anim('bow', 1.6, 'once', {'body': [kf(R, 0, 0), kf(R, 0.5, -35), kf(R, 1.1, -35), kf(R, 1.6, 0)],
                              'head': [kf(R, 0, 0), kf(R, 0.5, -20), kf(R, 1.1, -20), kf(R, 1.6, 0)],
                              'arm1': [kf(R, 0, 0), kf(R, 0.5, -15), kf(R, 1.1, -15), kf(R, 1.6, 0)],
                              'arm2': [kf(R, 0, 0), kf(R, 0.5, -15), kf(R, 1.1, -15), kf(R, 1.6, 0)]}),
    anim('beckon', 1.6, 'once', {'arm1': [kf(R, 0, 0), kf(R, 0.2, 75), kf(R, 0.5, 40), kf(R, 0.8, 75), kf(R, 1.1, 40), kf(R, 1.6, 0)],
                                 'head': [kf(R, 0, 0), kf(R, 0.3, 0, 15), kf(R, 1.2, 0, 15), kf(R, 1.6, 0)]}),
    anim('point', 1.4, 'once', {'arm1': [kf(R, 0, 0), kf(R, 0.25, 90), kf(R, 1.1, 90), kf(R, 1.4, 0)],
                                'head': [kf(R, 0, 0), kf(R, 0.25, -5), kf(R, 1.1, -5), kf(R, 1.4, 0)]}),
    anim('warn', 1.2, 'once', {'arm1': [kf(R, 0, 0), kf(R, 0.2, 85, 0, -35), kf(R, 0.9, 85, 0, -35), kf(R, 1.2, 0)],
                               'arm2': [kf(R, 0, 0), kf(R, 0.2, 85, 0, 35), kf(R, 0.9, 85, 0, 35), kf(R, 1.2, 0)],
                               'body': [kf(R, 0, 0), kf(R, 0.2, 6), kf(R, 0.9, 6), kf(R, 1.2, 0)]}),
]
names = {g['name'] for g in GESTURES}
d['animations'] = [a for a in d.get('animations', []) if a['name'] not in names] + GESTURES
json.dump(d, open(OUT, 'w', encoding='utf-8'))
print('animations', [a['name'] for a in d['animations']])
