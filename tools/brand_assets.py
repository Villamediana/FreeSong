from PIL import Image, ImageDraw, ImageFilter
import math, os, sys
RES=sys.argv[1]
WHITE=(255,255,255,255)
MINT1=(0xB8,0xFF,0xEA,255); MINT2=(0x8C,0xF5,0xD9,255); MINT3=(0x5F,0xD9,0xBC,255)
CUSH=(0xDD,0xE3,0xF5,255)
def bez(p0,p1,p2,p3,n=30):
    return [((1-t)**3*p0[0]+3*(1-t)**2*t*p1[0]+3*(1-t)*t*t*p2[0]+t**3*p3[0],(1-t)**3*p0[1]+3*(1-t)**2*t*p1[1]+3*(1-t)*t*t*p2[1]+t**3*p3[1]) for t in [i/n for i in range(n+1)]]
def feather(d,cx,cy,length,width,angle,fill):
    a=math.radians(angle); ux,uy=math.cos(a),math.sin(a); nx,ny=-uy,ux
    tip=(cx+ux*length, cy+uy*length)
    p1=(cx+ux*length*0.35+nx*width, cy+uy*length*0.35+ny*width)
    p2=(cx+ux*length*0.85+nx*width*0.7, cy+uy*length*0.85+ny*width*0.7)
    q1=(cx+ux*length*0.85-nx*width*0.25, cy+uy*length*0.85-ny*width*0.25)
    q2=(cx+ux*length*0.35-nx*width*0.35, cy+uy*length*0.35-ny*width*0.35)
    d.polygon(bez((cx,cy),p1,p2,tip)+bez(tip,q1,q2,(cx,cy)),fill=fill)
def art(N, unit, mono=None):
    """Headphones with wings on a transparent N×N canvas; `unit` = size of the old tile it was drawn on."""
    im=Image.new('RGBA',(N,N),(0,0,0,0)); d=ImageDraw.Draw(im); c=N/2; T=unit
    cols=[mono]*3 if mono else [MINT1,MINT2,MINT3]; white=mono or WHITE; cush=mono and (0,0,0,0) or CUSH
    R=0.20*T; band=0.055*T; cw=0.11*T; ch=0.19*T; cy=c-0.03*T
    for sgn in (-1,1):
        ox=c+sgn*(R*0.98+0.055*T); oy=c+0.05*T
        for (ln,wd,ang),col in zip([(1.00,0.30,-28),(0.86,0.27,-8),(0.70,0.24,12)],cols):
            feather(d,ox,oy,ln*0.19*T,wd*0.19*T*(1 if sgn>0 else -1),ang if sgn>0 else 180-ang,col)
    d.arc([c-R,cy-R,c+R,cy+R],195,345,fill=white,width=int(band))
    for sgn in (-1,1):
        ang=math.radians(195 if sgn<0 else 345); ex=c+R*math.cos(ang); ey=cy+R*math.sin(ang); r=band/2
        d.ellipse([ex-r,ey-r,ex+r,ey+r],fill=white)
        x=c+sgn*R*0.98; y=cy+0.07*T+0.03*T
        d.rounded_rectangle([x-band*0.32,ey,x+band*0.32,y-ch/2+4],radius=int(band*0.3),fill=white)
        d.rounded_rectangle([x-cw/2,y-ch/2,x+cw/2,y+ch/2],radius=int(cw*0.45),fill=white)
        if not mono:
            d.rounded_rectangle([x-cw/2+(cw*0.42 if sgn<0 else 0),y-ch/2+ch*0.18,x+cw/2-(0 if sgn<0 else cw*0.42),y+ch/2-ch*0.18],radius=int(cw*0.2),fill=cush)
    return im
def gradient(N, c1=(0x1C,0x1E,0x2A), c2=(0x2A,0x2D,0x45)):
    g=Image.new('RGBA',(N,N)); px=g.load(); c=N/2; a=math.radians(45); ca,sa=math.cos(a),math.sin(a)
    for y in range(N):
        for x in range(N):
            t=min(1,max(0,((x-c)*ca+(y-c)*sa)/(N*0.72)+0.5)); px[x,y]=tuple(int(c1[i]+(c2[i]-c1[i])*t) for i in range(3))+(255,)
    return g
def save(img,path,size):
    os.makedirs(os.path.dirname(path),exist_ok=True); img.resize((size,size),Image.LANCZOS).save(path)
BIG=1728  # 4× xxxhdpi
# adaptive foreground: art must fit the 66/108 safe zone → draw as if the old tile were 0.70 of the canvas
fg=art(BIG, BIG*0.76); bg=gradient(BIG//4).resize((BIG,BIG)); mono=art(BIG, BIG*0.76, mono=(255,255,255,255))
for dens,px in [('mdpi',108),('hdpi',162),('xhdpi',216),('xxhdpi',324),('xxxhdpi',432)]:
    save(fg,f'{RES}/mipmap-{dens}/ic_launcher_foreground.png',px)
    save(bg,f'{RES}/mipmap-{dens}/ic_launcher_background.png',px)
    save(mono,f'{RES}/mipmap-{dens}/ic_launcher_monochrome.png',px)
# in-app mark and splash: the full rounded tile
tile=Image.new('RGBA',(BIG,BIG),(0,0,0,0)); m=Image.new('L',(BIG,BIG),0); ImageDraw.Draw(m).rounded_rectangle([0,0,BIG-1,BIG-1],radius=int(BIG*0.23),fill=255)
tile.paste(gradient(BIG//4).resize((BIG,BIG)),(0,0),m); tile=Image.alpha_composite(tile,art(BIG,BIG))
save(tile,f'{RES}/drawable-nodpi/freesong_mark.png',512)
splash=Image.new('RGBA',(BIG,BIG),(0,0,0,0)); s=int(BIG*0.6); splash.paste(tile.resize((s,s),Image.LANCZOS),((BIG-s)//2,(BIG-s)//2))
save(splash,f'{RES}/drawable-nodpi/freesong_splash.png',768)
# notification: white silhouette, bigger in its 24dp box
note=art(BIG, BIG*1.05, mono=(255,255,255,255))
for dens,px in [('mdpi',24),('hdpi',36),('xhdpi',48),('xxhdpi',72),('xxxhdpi',96)]:
    save(note,f'{RES}/drawable-{dens}/ic_notification.png',px)
print('ok')
