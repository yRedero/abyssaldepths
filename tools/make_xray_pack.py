"""Builds a test resource pack that shows only Numinor ore: the rock around it turns invisible.

    python tools/make_xray_pack.py [resourcepacks dir]     (default: run/resourcepacks)

Face culling is decided by the blocks, not by models, so a pack alone can not see through rock to an
ore. Instead the ore's model draws its faces always (no cullface) with the purple veins at full
brightness, and the models of the rock blocks are emptied. Water and lava stay (they are no block models).
Rebuild after adding blocks to the mod; the mod's blocks are read from AbyssalDepths.java.
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.join(os.path.dirname(__file__), '..')
KEEP = {'numinor_ore'}
VANILLA = [
    'stone', 'deepslate', 'cobbled_deepslate', 'tuff', 'granite', 'diorite', 'andesite', 'calcite', 'gravel',
    'dirt', 'coarse_dirt', 'clay', 'smooth_basalt', 'basalt', 'bedrock', 'magma_block', 'obsidian', 'sand',
    'sandstone', 'netherrack', 'dripstone_block', 'pointed_dripstone', 'moss_block', 'glow_lichen',
    'amethyst_block', 'budding_amethyst', 'cobblestone', 'mossy_cobblestone', 'infested_stone', 'infested_deepslate',
    'coal_ore', 'iron_ore', 'copper_ore', 'gold_ore', 'redstone_ore', 'lapis_ore', 'diamond_ore', 'emerald_ore',
    'deepslate_coal_ore', 'deepslate_iron_ore', 'deepslate_copper_ore', 'deepslate_gold_ore',
    'deepslate_redstone_ore', 'deepslate_lapis_ore', 'deepslate_diamond_ore', 'deepslate_emerald_ore',
    'raw_iron_block', 'raw_copper_block']


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(data, indent=2) + '\n')


def main(target):
    pack = os.path.join(target, 'Numinor X-Ray')
    shutil.rmtree(pack, ignore_errors=True)
    write(os.path.join(pack, 'pack.mcmeta'), {'pack': {
        'pack_format': 34, 'description': 'Abyssal Depths test: only Numinor ore is visible'}})

    source = open(os.path.join(ROOT, 'src', 'main', 'java', 'redero', 'abyssaldepths', 'AbyssalDepths.java'), encoding='utf-8').read()
    mod_blocks = set(re.findall(r'BLOCKS\.register\w*\(\s*"(\w+)"', source))
    mod_blocks |= set(re.findall(r'\b(?:building|siltBuilding|stairs|slab|wall|ore)\("(\w+)"', source))

    # an empty model, applied to every state of the hidden blocks (multipart: works with any properties)
    write(os.path.join(pack, 'assets', 'abyssaldepths', 'models', 'block', 'xray_hidden.json'),
          {'textures': {'particle': 'minecraft:block/stone'}, 'elements': []})
    hidden = {'abyssaldepths': sorted(mod_blocks - KEEP), 'minecraft': VANILLA}
    for namespace, blocks in hidden.items():
        for block in blocks:
            write(os.path.join(pack, 'assets', namespace, 'blockstates', block + '.json'),
                  {'multipart': [{'apply': {'model': 'abyssaldepths:block/xray_hidden'}}]})

    # the ore: every face always drawn, veins at full brightness
    model_path = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'abyssaldepths', 'models', 'block', 'numinor_ore.json')
    model = json.load(open(model_path, encoding='utf-8'))
    for element in model['elements']:
        for face in element['faces'].values():
            face.pop('cullface', None)
    write(os.path.join(pack, 'assets', 'abyssaldepths', 'models', 'block', 'numinor_ore.json'), model)
    print(f'{os.path.abspath(pack)}: {sum(len(b) for b in hidden.values())} blocks hidden, Numinor always drawn')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'run', 'resourcepacks'))
