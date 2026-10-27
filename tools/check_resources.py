"""Checks that every block, sound and model of the mod has what it needs (run before committing).

    python tools/check_resources.py

- every registered block: blockstate, item model, loot table, English name
- every texture a model refers to exists (or is a model of its own)
- every registered sound event has an entry in sounds.json
- all JSON files parse
Exits with 1 and lists the problems if there are any.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), '..')
MAIN = os.path.join(ROOT, 'src', 'main')
ASSETS = os.path.join(MAIN, 'resources', 'assets', 'abyssaldepths')
DATA = os.path.join(MAIN, 'resources', 'data', 'abyssaldepths')


def main():
    problems = []
    source = open(os.path.join(MAIN, 'java', 'redero', 'abyssaldepths', 'AbyssalDepths.java'), encoding='utf-8').read()
    blocks = set(re.findall(r'BLOCKS\.register\w*\(\s*"(\w+)"', source))
    blocks |= set(re.findall(r'\b(?:building|siltBuilding|stairs|slab|wall|ore)\("(\w+)"', source))

    for path in glob.glob(os.path.join(MAIN, 'resources', '**', '*.json'), recursive=True):
        try:
            json.load(open(path, encoding='utf-8'))
        except ValueError as e:
            problems.append(f'broken JSON {os.path.relpath(path, ROOT)}: {e}')

    lang = json.load(open(os.path.join(ASSETS, 'lang', 'en_us.json'), encoding='utf-8'))
    for block in sorted(blocks):
        for what, path in [('blockstate', os.path.join(ASSETS, 'blockstates', block + '.json')),
                           ('item model', os.path.join(ASSETS, 'models', 'item', block + '.json')),
                           ('loot table', os.path.join(DATA, 'loot_table', 'blocks', block + '.json'))]:
            if not os.path.exists(path):
                problems.append(f'{block}: no {what}')
        if 'block.abyssaldepths.' + block not in lang:
            problems.append(f'{block}: no English name')

    for path in glob.glob(os.path.join(ASSETS, 'models', '*', '*.json')):
        for ref in re.findall(r'"abyssaldepths:(?:block|item)/(\w+)"', open(path, encoding='utf-8').read()):
            kind = 'item' if '/item/' in path.replace('\\', '/') and os.path.exists(os.path.join(ASSETS, 'textures', 'item', ref + '.png')) else 'block'
            if not (os.path.exists(os.path.join(ASSETS, 'textures', kind, ref + '.png'))
                    or os.path.exists(os.path.join(ASSETS, 'models', 'block', ref + '.json'))):
                problems.append(f'{os.path.relpath(path, ROOT)}: missing texture {ref}')

    sounds = json.load(open(os.path.join(ASSETS, 'sounds.json'), encoding='utf-8'))
    for event in re.findall(r'\bsound\("([\w.]+)"\)', source):
        if event not in sounds:
            problems.append(f'sound event {event}: no entry in sounds.json')

    for p in problems:
        print('  ' + p)
    print(f'{len(blocks)} blocks, {len(sounds)} sound events checked: {len(problems)} problems')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
