"""Turns the autoshot screenshots into something quick to look at (used by tools/autoshot.sh).

    python tools/shots.py <screenshot dir> <out dir>

Writes downscaled JPGs, brightened copies in bright/ (x2.5: the layer is dark by design) and one
contact sheet per spot with its four views (sheet_<biome>_<n>.jpg, brightened).
"""
import glob
import os
import sys

from PIL import Image


def main(src, out):
    os.makedirs(os.path.join(out, 'bright'), exist_ok=True)
    spots = {}
    for path in sorted(glob.glob(os.path.join(src, 'autoshot_*.png'))):
        name = os.path.basename(path)[len('autoshot_'):-4]  # e.g. silt_dunes_0_dir2
        image = Image.open(path).convert('RGB')
        image.thumbnail((960, 540))
        image.save(os.path.join(out, name + '.jpg'), quality=85)
        bright = image.point(lambda v: min(255, int(v * 2.5)))
        bright.save(os.path.join(out, 'bright', name + '.jpg'), quality=85)
        spot, view = name.rsplit('_', 1)
        spots.setdefault(spot, {})[view] = bright
    for spot, views in spots.items():
        sheet = Image.new('RGB', (1920, 1080))
        for i, view in enumerate(['dir0', 'dir1', 'dir2', 'dir3']):
            if view in views:
                sheet.paste(views[view].resize((960, 540)), ((i % 2) * 960, (i // 2) * 540))
        sheet.save(os.path.join(out, 'sheet_' + spot + '.jpg'), quality=85)
    print(sum(len(v) for v in spots.values()), 'shots,', len(spots), 'sheets in', out)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
