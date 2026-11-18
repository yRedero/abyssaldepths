"""Draws the Shadeborn trading screen's texture (512x256; the screen 276x166 at 0,0, sprites below it). The inventory part
(x 100 on, y 70 on) stays transparent: the game's own inventory is drawn there."""
import random
from PIL import Image

OUT = 'src/main/resources/assets/abyssaldepths/textures/gui/shadeborn_trade.png'
r = random.Random(11)
im = Image.new('RGBA', (512, 256), (0, 0, 0, 0))
px = im.load()


def c(h, a=255):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, a)


def jitter(col, k=3):
    d = r.randint(-k, k)
    return tuple(max(0, min(255, v + d)) for v in col[:3]) + (col[3],)


def rect(x0, y0, x1, y1, col, noise=0):
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = jitter(col, noise) if noise else col


PANEL, LIGHT, DARK = c(0x16121A), c(0x2E2634), c(0x07050A)
WELL, WELL_EDGE = c(0x0C0A0F), c(0x241E2A)
VIOLET, RED = c(0x725276), c(0x96253E)
W, H = 276, 166


def panel(x0, y0, x1, y1):
    rect(x0, y0, x1, y1, DARK)
    rect(x0, y0, x1 - 1, y1 - 1, LIGHT)
    rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL, 2)
    # rounded-off corners, like vanilla
    for x, y in ((x0, y0), (x1 - 1, y0), (x0, y1 - 1), (x1 - 1, y1 - 1)):
        px[x, y] = (0, 0, 0, 0)


def well(x0, y0, x1, y1):
    rect(x0, y0, x1, y1, WELL_EDGE)
    rect(x0, y0, x1 - 1, y1 - 1, DARK)
    rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, WELL, 1)


panel(0, 0, W, H)
# an inner line along the frame, a pixel in: the panel's border
for x in range(3, W - 3):
    px[x, 3] = jitter(c(0x1E1924))
    px[x, H - 4] = jitter(c(0x0E0B12))
for y in range(3, H - 3):
    px[3, y] = jitter(c(0x1E1924))
    px[W - 4, y] = jitter(c(0x0E0B12))
# a few faint cracks in the corners, a hint of red in them (the layer's stone)
for sx, sy, dx, dy in ((5, 5, 1, 1), (W - 6, H - 6, -1, -1)):
    x, y = sx, sy
    for i in range(7):
        px[x, y] = c(0x3A1A24) if i % 3 else c(0x5A2232)
        x += dx if r.random() < 0.6 else 0
        y += dy if r.random() < 0.6 else 0
# the red line under the title
for x in range(107, 167):
    px[x, 15] = c(0x96253E, 110 - (x - 107))
# the list well (6..98, 18..158)
well(4, 16, 100, 160)
# slots: payment, payment, result (the result's well larger), inventory, hotbar
for sx, sy in ((136, 37), (162, 37)):
    well(sx - 1, sy - 1, sx + 17, sy + 17)
well(220 - 5, 37 - 5, 220 + 21, 37 + 21)
rect(220 - 4, 37 - 4, 220 + 20, 37 - 3, c(0x1A1420))
# the inventory (x 100.., y 70..) is left open: the game's own is drawn there (only the trade part is ours)
for y in range(69, H):
    for x in range(99, W):
        px[x, y] = (0, 0, 0, 0)
for y in range(69, H):
    px[99, y] = DARK
for x in range(99, W):
    px[x, 69] = DARK


def arrow(ox, oy, col):
    rect(ox, oy, ox + 16, oy + 2, col)
    for k in range(4):
        rect(ox + 12 + k, oy - 3 + k, ox + 13 + k, oy + 5 - k, col)


arrow(188, 44, WELL_EDGE)
# sprites below the screen: the lit arrow (0,170 as 16x8 box, its line at y 173), selected row, hovered row
arrow(0, 173, VIOLET)
for y in range(190, 210):
    for x in range(0, 92):
        px[x, y] = c(0x725276, 64)
    px[0, y] = VIOLET
for y in range(212, 232):
    for x in range(0, 92):
        px[x, y] = c(0x725276, 34)
im.save(OUT)
print('saved', OUT)
