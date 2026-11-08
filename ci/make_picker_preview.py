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
for label,angle,c in [('N',0,'#FF5555'),('E',90,'#F8F8F2'),
                      ('S',180,'#F8F8F2'),('W',270,'#F8F8F2')]:
    x=233+212*math.sin(math.radians(angle))
    y=233-212*math.cos(math.radians(angle))
    center(label,x,y+9,22,c,True)

# Original Arcs quadrants: outline and progress are beneath compass
rect=circle_bbox(233,233,188)
for start,end,color,frac in [
    (285,352,'#FFB86C',0.8),
    (8,75,'#50FA7B',0.75),
    (105,172,'#8BE9FD',0.72),
    (188,255,'#50FA7B',0.85)]:
    d.arc(rect,start-90,end-90,fill='#44475A',width=p(10))
    d.arc(rect,start-90,start-90+(end-start)*frac,fill=color,width=p(10))

# Center disks and data
for cx in [165,301]:
    d.ellipse(circle_bbox(cx,285,46),fill='#1F202A')
center("Sat, Nov 7",233,137,27,'#F8F8F2')
center("10:08",233,228,99,'#F8F8F2',True)
center("●",165,291,43,'#50FA7B')
center("●",301,291,43,'#FF79C6')
center("6954",165,323,24,'#F8F8F2',True)
center("76",301,323,24,'#F8F8F2',True)

# 12 o'clock pointer, heading, and representative ring readouts
d.polygon([(p(233),p(7)),(p(225),p(25)),(p(241),p(25))],fill="#FF5555")
center("000°",233,403,25,'#BD93F9',True)

def tangent_label(text,bearing,color):
    """Representative icon/value group with the same 94px tangent envelope."""
    f=font(24,True)
    scratch=Image.new("RGBA",(p(200),p(48)),(0,0,0,0))
    pen=ImageDraw.Draw(scratch)
    tw=pen.textbbox((0,0),text,font=f)
    w=tw[2]-tw[0]
    text_image=Image.new("RGBA",(max(1,w),p(34)),(0,0,0,0))
    text_pen=ImageDraw.Draw(text_image)
    text_pen.text((0,-tw[1]),text,font=f,fill="#F8F8F2")
    if w>p(60):
        text_image=text_image.resize((p(60),text_image.height),Image.Resampling.LANCZOS)
    total=p(26+8)+text_image.width
    local=Image.new("RGBA",(total,p(36)),(0,0,0,0))
    ld=ImageDraw.Draw(local)
    ld.rounded_rectangle((p(1),p(6),p(20),p(24)),radius=p(4),outline=color,width=p(3))
    ld.rectangle((p(21),p(12),p(25),p(18)),fill=color)
    local.paste(text_image,(p(34),p(3)),text_image)
    orientation=bearing if bearing<90 or bearing>270 else bearing-180
    rotated=local.rotate(-orientation,expand=True,resample=Image.Resampling.BICUBIC)
    x=233+157*math.sin(math.radians(bearing))
    y=233-157*math.cos(math.radians(bearing))
    img.paste(rotated,(p(x)-rotated.width//2,p(y)-rotated.height//2),rotated)

for text_value,bearing,tint in [
    ("6954",315,"#FFB86C"),("76%",45,"#50FA7B"),
    ("81%",137,"#8BE9FD"),("07",223,"#50FA7B")
]:
    tangent_label(text_value,bearing,tint)

img.resize((SIZE,SIZE),Image.Resampling.LANCZOS).save(OUT,optimize=True)
print(f"Wrote picker bitmap: {OUT}")
