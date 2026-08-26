#!/usr/bin/env python3
import struct, zlib, math

def make_png(path, w, h, px):
    def chunk(typ, data):
        c = typ + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    raw = b''
    for y in range(h):
        row = b'\x00'
        for x in range(w):
            r, g, b = px[y][x]
            row += bytes((r, g, b))
        raw += row
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    with open(path, 'wb') as f:
        f.write(png)

S = 192
CENTER = S / 2
# 背景: 浅灰
bg = (246, 246, 246)
# 圆角矩形底: 深墨黑
panel = (17, 17, 17)
# 表盘: 白
face = (255, 255, 255)
# 指针: 红
ink = (198, 40, 40)

px = [[bg for _ in range(S)] for _ in range(S)]

def inside_round(x, y, cx, cy, r, rad):
    xa = x - cx; ya = y - cy
    if xa < -rad or xa > rad or ya < -rad or ya > rad:
        return False
    if xa >= rad or xa <= -rad or ya >= rad or ya <= -rad:
        return (xa*xa + ya*ya) <= r*r
    return True

def in_r(x, y, cx, cy, rx, ry):
    return ((x-cx)/rx)**2 + ((y-cy)/ry)**2 <= 1.0

# 圆角方形底 (panel), 居中占多数
for y in range(S):
    for x in range(S):
        if inside_round(x+0.5, y+0.5, CENTER, CENTER, S/2, S/2-8):
            px[y][x] = panel

# 表盘 (白色圆)
R = S*0.26
for y in range(S):
    for x in range(S):
        if in_r(x+0.5, y+0.5, CENTER, CENTER, R, R):
            px[y][x] = face

# 时针 (指向右/上), 用线段
def draw_line(x0, y0, x1, y1, color, width):
    n = int(max(abs(x1-x0), abs(y1-y0))) * 2
    for i in range(n+1):
        t = i / n
        cx = x0 + (x1-x0)*t
        cyl = y0 + (y1-y0)*t
        for dx in range(-width, width+1):
            for dy in range(-width, width+1):
                xn = int(round(cx+dx)); yn = int(round(cyl+dy))
                if 0 <= xn < S and 0 <= yn < S:
                    px[yn][xn] = color

# 分针 (长): 中心到上方
draw_line(CENTER, CENTER, CENTER, CENTER - R*0.62, ink, 5)
# 时针 (短): 中心到右
draw_line(CENTER, CENTER, CENTER + R*0.40, CENTER, ink, 7)
# 中心点
for dx in range(-6, 7):
    for dy in range(-6, 7):
        if dx*dx+dy*dy <= 36:
            px[int(CENTER+dy)][int(CENTER+dx)] = ink

make_png('/opt/taskrun/res/drawable/ic_launcher.png', S, S, px)
print("icon written")
