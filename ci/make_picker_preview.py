#!/usr/bin/env python3
"""Generate a genuine PNG bitmap for OEM watch-face pickers.

Unlike VectorDrawable resources, this preview can be decoded by BitmapFactory
on firmware that assumes bitmap previews. It is deliberately deterministic.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

OUT = Path("legacy/src/main/res/drawable-nodpi/preview_bitmap.png")
OUT.parent.mkdir(parents=True, exist_ok=True)
SIZE = 466
S = 2  # supersampling for antialiased arcs and small compass markings
img = Image.new("RGB", (SIZE*S,SIZE*S), "#000000")
d = ImageDraw.Draw(img)

def p(v): return int(round(v*S))
def circle_bbox(cx,cy,r): return (p(cx-r),p(cy-r),p(cx+r),p(cy+r))
def font(size, bold=False):
    root = "/usr/share/fonts/truetype/dejavu"
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    try: return ImageFont.truetype(f"{root}/{name}", p(size))
    except OSError: return ImageFont.load_default()
def center(text, cx, baseline, size, color, bold=False):
    f=font(size,bold)
    bbox=d.textbbox((0,0),text,font=f)
    w=bbox[2]-bbox[0]
    d.text((p(cx)-w/2,p(baseline)-(bbox[3]-bbox[1])-bbox[1]),text,
           fill=color,font=f)

# Prominent compass ring around the face
d.ellipse(circle_bbox(233,233,224),outline="#6272A4",width=p(2.5))
for deg in range(0,360,15):
    if deg%90==0: continue
    phi=math.radians(deg-90)
    start=212 if deg%45==0 else 217
    sx,sy=233+math.cos(phi)*start,233+math.sin(phi)*start
    ex,ey=233+math.cos(phi)*221,233+math.sin(phi)*221
    d.line((p(sx),p(sy),p(ex),p(ey)),fill="#F8F8F2" if deg%45==0 else "#6272A4",
           width=p(3 if deg%45==0 else 2))
for label,x,y,c in [('N',233,27,'#FF5555'),('E',434,241,'#F8F8F2'),
                    ('S',233,453,'#F8F8F2'),('W',33,241,'#F8F8F2')]:
    center(label,x,y,17,c,True)

# Original Arcs quadrants: outline and progress are beneath compass
rect=circle_bbox(233,233,194)
for start,end,color,frac in [
    (285,352,'#FFB86C',0.8),
    (8,75,'#50FA7B',0.75),
    (105,172,'#8BE9FD',0.72),
    (188,255,'#50FA7B',0.85)]:
    d.arc(rect,start-90,end-90,fill='#44475A',width=p(10))
    d.arc(rect,start-90,start-90+(end-start)*frac,fill=color,width=p(10))

# Center disks and data
for cx in [165,301]:
    d.ellipse(circle_bbox(cx,309,52),fill='#1F202A')
center("Sat, Nov 7",233,140,24,'#F8F8F2')
center("10:08",233,230,91,'#F8F8F2',True)
center("●",165,321,33,'#50FA7B')
center("●",301,321,33,'#FF79C6')
center("6954",165,348,18,'#F8F8F2',True)
center("76",301,348,18,'#F8F8F2',True)

# 12 o'clock pointer, heading, and representative ring readouts
d.polygon([(p(233),p(7)),(p(225),p(25)),(p(241),p(25))],fill="#FF5555")
center("078°",233,400,20,'#BD93F9',True)
center("6954",112,80,17,'#F8F8F2',True)
center("76%",364,80,17,'#F8F8F2',True)
center("07",113,376,17,'#F8F8F2',True)
center("81%",365,376,17,'#F8F8F2',True)

img.resize((SIZE,SIZE),Image.Resampling.LANCZOS).save(OUT,optimize=True)
print(f"Wrote picker bitmap: {OUT}")
