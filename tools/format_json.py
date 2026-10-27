"""Formats all JSON resources the same way: 2 space indent, keys in their order, LF, newline at the end.

    python tools/format_json.py [--check]

With --check nothing is written; it lists the files that are not formatted and exits with 1.
"""
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(__file__), '..')


def formatted(path):
    with open(path, encoding='utf-8') as f:
        return json.dumps(json.load(f), indent=2, ensure_ascii=False) + '\n'


def main(check):
    changed = []
    for path in sorted(glob.glob(os.path.join(ROOT, 'src', 'main', 'resources', '**', '*.json'), recursive=True)):
        text = formatted(path)
        with open(path, encoding='utf-8', newline='') as f:
            if f.read() == text:
                continue
        changed.append(os.path.relpath(path, ROOT))
        if not check:
            with open(path, 'w', encoding='utf-8', newline='\n') as f:
                f.write(text)
    for path in changed:
        print(('  not formatted: ' if check else '  formatted: ') + path)
    print(f'{len(changed)} files {"to format" if check else "formatted"}')
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main('--check' in sys.argv))
