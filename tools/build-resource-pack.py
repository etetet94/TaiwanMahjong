"""Build the 42 traditional Mahjong tiles from CC0 pictograms and vector flowers.

Run from any directory with Python + Pillow; all inputs are vendored.
Outputs resource-pack/dist/TaiwanMahjong_resource_pack.zip and docs/tile-preview.png.
"""
from __future__ import annotations

from hashlib import sha1, sha256
import json
import math
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent.parent
RESOURCE = ROOT / "resource-pack"
DIST = RESOURCE / "dist"
PACK = DIST / "pack"
NAMESPACE = "taiwanmahjong"
SOURCE = RESOURCE / "source" / "regular"
FONT = RESOURCE / "fonts" / "NotoSansCJKtc-Regular.otf"
ZIP_PATH = DIST / "TaiwanMahjong_resource_pack.zip"
NAMES = ([f"Man{i}" for i in range(1, 10)] + [f"Pin{i}" for i in range(1, 10)]
         + [f"Sou{i}" for i in range(1, 10)]
         + ["Ton", "Nan", "Shaa", "Pei", "Chun", "Hatsu", "Haku"]
         + list("春夏秋冬梅蘭竹菊"))
LABELS = ([f"{i}萬" for i in range(1, 10)] + [f"{i}筒" for i in range(1, 10)]
          + [f"{i}索" for i in range(1, 10)] + list("東南西北中發白春夏秋冬梅蘭竹菊"))
GREEN = (24, 113, 72, 255)
RED = (181, 48, 56, 255)
BLUE = (27, 49, 98, 255)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def font(size: int) -> ImageFont.FreeTypeFont:
    # Vendored SIL OFL font: never read platform-specific or proprietary fonts.
    return ImageFont.truetype(str(FONT), size)


def leaf(draw: ImageDraw.ImageDraw, start, end, width=35, color=GREEN) -> None:
    x0, y0 = start
    x1, y1 = end
    dx, dy = x1 - x0, y1 - y0
    length = max(math.hypot(dx, dy), 1)
    nx, ny = -dy / length * width, dx / length * width
    mx, my = x0 + dx * 0.53, y0 + dy * 0.53
    draw.polygon([(x0, y0), (mx + nx, my + ny), (x1, y1),
                  (mx - nx * 0.5, my - ny * 0.5)], fill=color)


def blossom(draw: ImageDraw.ImageDraw, center, radius=65, color=RED, petals=5) -> None:
    x, y = center
    r = radius * 0.57
    for k in range(petals):
        a = -math.pi / 2 + k * math.tau / petals
        cx, cy = x + math.cos(a) * radius * 0.53, y + math.sin(a) * radius * 0.53
        draw.ellipse((cx-r, cy-r, cx+r, cy+r), fill=color)
    draw.ellipse((x-radius*.20, y-radius*.20, x+radius*.20, y+radius*.20),
                 fill=(238, 191, 71, 255))
    for k in range(petals):
        a = k * math.tau / petals
        cx, cy = x + math.cos(a)*radius*.32, y + math.sin(a)*radius*.32
        draw.ellipse((cx-5, cy-5, cx+5, cy+5), fill=(255, 232, 161, 255))


def chrysanthemum(draw: ImageDraw.ImageDraw, center, radius=130) -> None:
    x, y = center
    for ring, count, scale, color in ((0, 18, 1.0, (211, 133, 32, 255)),
                                     (1, 15, .79, (240, 177, 45, 255)),
                                     (2, 12, .55, (250, 208, 81, 255))):
        for k in range(count):
            a = k * math.tau / count + ring * .14
            leaf(draw, (x, y), (x+math.cos(a)*radius*scale, y+math.sin(a)*radius*scale),
                 width=19, color=color)
    draw.ellipse((x-24, y-24, x+24, y+24), fill=(135, 78, 24, 255))


def flower(index: int) -> Image.Image:
    """Original deterministic vector illustrations: four seasons, four gentlemen."""
    im = Image.new("RGBA", (600, 800))
    d = ImageDraw.Draw(im)
    # Traditional corner name and suite number supplement the pictogram.
    color = RED if index < 4 else BLUE
    d.text((24, 12), str(index % 4 + 1), font=font(88), fill=color)
    d.text((455, 8), NAMES[34+index], font=font(108), fill=color)
    brown = (103, 70, 47, 255)
    if index in (0, 4):  # Spring cherry branch / plum branch.
        d.line([(120, 730), (250, 550), (310, 350), (450, 190)], fill=brown, width=25)
        d.line([(245, 550), (100, 380)], fill=brown, width=17)
        d.line([(306, 369), (470, 420)], fill=brown, width=15)
        petal = (226, 129, 148, 255) if index == 0 else RED
        for center in ((130, 390), (245, 510), (317, 340), (450, 200), (465, 420)):
            blossom(d, center, 69, petal)
        for start, end in (((220, 600), (120, 560)), ((275, 470), (375, 475)),
                           ((340, 300), (400, 330))):
            leaf(d, start, end, 27)
    elif index == 1:  # Summer lotus.
        d.line([(300, 700), (300, 390)], fill=GREEN, width=19)
        d.ellipse((90, 590, 505, 730), fill=(41, 133, 78, 255))
        d.line((300, 655, 480, 614), fill=(103, 166, 84, 255), width=8)
        for k, angle in enumerate((-2.5, -2.1, -1.57, -1.05, -.65)):
            leaf(d, (300, 480), (300+math.cos(angle)*245, 480+math.sin(angle)*245),
                 74, (198+9*(k%2), 62+22*(k%2), 114+20*(k%2), 255))
        blossom(d, (300, 451), 52, (232, 127, 147, 255), 5)
    elif index == 2:  # Autumn maple branch.
        d.line([(170, 730), (300, 440), (415, 250)], fill=brown, width=22)
        for x, y, r, a in ((200, 440, 135, -.35), (410, 270, 128, .35), (400, 605, 145, .8)):
            d.line([(290, 490), (x, y)], fill=brown, width=13)
            pts=[]
            for k in range(14):
                angle=a+math.tau*k/14
                size=r*(1 if k % 2 == 0 else .42)
                pts.append((x+math.cos(angle)*size,y+math.sin(angle)*size))
            d.polygon(pts, fill=(204, 93+(int(r)%3)*20, 32, 255))
            d.line((x-r*.5,y+r*.4,x+r*.4,y-r*.5),fill=(245,174,63,255),width=7)
    elif index == 3:  # Winter pine with snowy branches.
        d.line([(165, 730), (270, 520), (340, 350), (415, 180)], fill=brown, width=25)
        for x,y in ((230,580),(305,410),(380,245)):
            for side in (-1,1):
                end=(x+side*125,y-55)
                d.line([(x,y),end],fill=brown,width=12)
                for k in range(6):
                    xx=x+side*(25+k*19); yy=y-(25+k*6)
                    d.line([(xx,yy),(xx+side*35,yy-90)],fill=GREEN,width=13)
                    d.line([(xx,yy),(xx-side*15,yy-75)],fill=GREEN,width=11)
                d.line([(x+side*40,y-90),(x+side*135,y-125)],fill=(155,190,198,255),width=20)
                d.line([(x+side*40,y-96),(x+side*135,y-131)],fill=(245,249,243,255),width=12)
    elif index == 5:  # Orchid.
        for end in ((110,390),(170,250),(420,300),(490,480)):
            leaf(d,(285,740),end,30)
        d.line([(285,740),(300,480),(255,260)],fill=GREEN,width=14)
        d.line([(300,490),(425,380)],fill=GREEN,width=12)
        blossom(d,(250,265),102,(124,90,153,255),5)
        blossom(d,(425,395),85,(151,109,171,255),5)
        leaf(d,(250,275),(260,355),28,(209,147,180,255))
    elif index == 6:  # Bamboo, segmented stems and pointed leaves.
        for x,top,bottom in ((245,180,745),(370,290,745)):
            d.line((x,top,x-45,bottom),fill=GREEN,width=30)
            for y in range(top+80,bottom,115):
                xx=x-45*(y-top)/(bottom-top)
                d.line((xx-20,y,xx+20,y),fill=(225,230,177,255),width=9)
        for x,y,side in ((230,340,-1),(225,455,1),(352,500,1),(340,620,-1)):
            d.line([(x,y),(x+side*100,y-80)],fill=GREEN,width=12)
            for k in range(3):
                leaf(d,(x+side*(20+k*30),y-20-k*20),
                     (x+side*(40+k*55),y-140-k*15),20)
                leaf(d,(x+side*(20+k*30),y-20-k*20),
                     (x+side*(100+k*35),y-35-k*25),18)
    else:  # Chrysanthemum.
        d.line([(270,740),(300,470),(315,310)],fill=GREEN,width=20)
        d.line([(290,530),(450,470)],fill=GREEN,width=13)
        for start,end in (((280,650),(120,550)),((288,565),(405,610)),((300,460),(155,400))):
            leaf(d,start,end,50)
        chrysanthemum(d,(300,300),150)
        chrysanthemum(d,(450,470),88)
    return im


def render_item(glyph: Image.Image) -> Image.Image:
    # Work at 4x output resolution for clean edges at GUI scale.
    canvas = Image.new("RGBA", (512, 512))
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((110, 30, 410, 502), radius=30, fill=(0, 0, 0, 50))
    d.rounded_rectangle((101, 16, 401, 492), radius=29,
                        fill=(24, 105, 74, 255), outline=(16, 72, 55, 255), width=8)
    d.rounded_rectangle((95, 8, 395, 456), radius=27,
                        fill=(249, 246, 228, 255), outline=(201, 189, 157, 255), width=7)
    d.rounded_rectangle((106, 18, 384, 447), radius=20,
                        outline=(255, 253, 241, 255), width=6)
    art = glyph.resize((264, 352), Image.Resampling.LANCZOS)
    canvas.alpha_composite(art, (113, 52))
    return canvas.resize((128, 128), Image.Resampling.LANCZOS)


def render_font(glyph: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", (64, 128))
    art = glyph.resize((60, 80), Image.Resampling.LANCZOS)
    canvas.alpha_composite(art, (2, 24))
    # Minecraft trims bitmap advances to the last nontransparent pixel. Alpha=1
    # anchors preserve the full 64-pixel cell for all tiles, including blank Haku,
    # while remaining visually imperceptible on the physical ivory tile.
    canvas.putpixel((0, 64), (249, 246, 228, 1))
    canvas.putpixel((63, 64), (249, 246, 228, 1))
    return canvas


def main() -> None:
    assert len(NAMES) == len(LABELS) == 42
    write_json(PACK / "pack.mcmeta", {"pack": {
        "description": "Taiwan Mahjong · Traditional tile pictograms",
        "min_format": [97, 1], "max_format": [97, 1]}})
    assets = PACK / "assets" / NAMESPACE
    providers = []
    manifest = []
    item_images = []
    font_images = []
    for i, name in enumerate(NAMES):
        source = SOURCE / (name + ".png") if i < 34 else None
        glyph = (Image.open(source).convert("RGBA")
                 if source else flower(i - 34))
        item = render_item(glyph)
        tile_font = render_font(glyph)
        for kind, picture in (("item", item), ("font", tile_font)):
            output = assets / "textures" / kind / f"tile_{i}.png"
            output.parent.mkdir(parents=True, exist_ok=True)
            picture.save(output, optimize=True)
        write_json(assets / "items" / f"tile_{i}.json", {
            "model": {"type": "minecraft:model", "model": f"{NAMESPACE}:item/tile_{i}"}})
        write_json(assets / "models" / "item" / f"tile_{i}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{NAMESPACE}:item/tile_{i}"}})
        providers.append({"type": "bitmap", "file": f"{NAMESPACE}:font/tile_{i}.png",
                          "ascent": 16, "height": 32, "chars": [chr(0xE100 + i)]})
        manifest.append({"id": i, "name": name, "label": LABELS[i],
                         "codepoint": f"U+{0xE100+i:04X}",
                         "item_model": f"{NAMESPACE}:tile_{i}",
                         "source": "source/regular/" + name + ".png" if source else "Original deterministic illustration"})
        item_images.append(item)
        font_images.append(tile_font)
    write_json(assets / "font" / "tiles.json", {"providers": providers})
    write_json(DIST / "tiles-manifest.json", {"tiles": manifest})
    license_text = (RESOURCE / "LICENSE.txt").read_text(encoding="utf-8")
    (PACK / "LICENSE.txt").write_text(license_text, encoding="utf-8")
    (PACK / "OFL.txt").write_bytes((RESOURCE / "fonts" / "OFL.txt").read_bytes())

    # Six rows of seven, readable labels plus the actual 128px GUI textures.
    sheet = Image.new("RGB", (7*154, 6*180+70), (40, 65, 56))
    sd = ImageDraw.Draw(sheet)
    sd.text((20, 14), "Taiwan Mahjong · 42 traditional tiles", font=font(28), fill=(245, 239, 214))
    for i, item in enumerate(item_images):
        x, y = (i % 7)*154, (i // 7)*180+64
        sheet.paste(item, (x+13, y), item)
        sd.text((x+15, y+132), f"{i:02d}  {LABELS[i]}", font=font(22), fill=(245, 239, 214))
    (ROOT / "docs").mkdir(parents=True, exist_ok=True)
    sheet.save(ROOT / "docs" / "tile-preview.png")

    font_sheet = Image.new("RGBA", (7*76, 6*140), (249, 246, 228, 255))
    for i, glyph in enumerate(font_images):
        font_sheet.alpha_composite(glyph, ((i % 7)*76+6, (i // 7)*140+6))
    font_sheet.convert("RGB").save(DIST / "font-contact-sheet.png")

    # Validate references before packaging; every glyph must keep its full advance.
    assert [ord(p["chars"][0]) for p in providers] == list(range(0xE100, 0xE12A))
    for i, bitmap in enumerate(font_images):
        assert bitmap.size == (64, 128) and bitmap.getbbox()[2] == 64
        assert (assets / "textures" / "item" / f"tile_{i}.png").is_file()
        assert (assets / "items" / f"tile_{i}.json").is_file()
        assert (assets / "models" / "item" / f"tile_{i}.json").is_file()
    for directory in (assets / "items", assets / "models" / "item", assets / "textures" / "item", assets / "textures" / "font"):
        assert len(list(directory.iterdir())) == 42, directory
    with ZipFile(ZIP_PATH, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(PACK.rglob("*")):
            if path.is_file():
                entry = ZipInfo(path.relative_to(PACK).as_posix(), date_time=(2026, 10, 23, 0, 0, 0))
                entry.compress_type = ZIP_DEFLATED
                entry.create_system = 3  # Stable ZIP metadata on Windows, macOS and Linux.
                entry.external_attr = 0o644 << 16
                archive.writestr(entry, path.read_bytes())
    with ZipFile(ZIP_PATH) as archive:
        assert archive.testzip() is None
        assert len(archive.namelist()) == 172
    print(json.dumps({"zip": str(ZIP_PATH), "bytes": ZIP_PATH.stat().st_size,
                      "sha1": sha1(ZIP_PATH.read_bytes()).hexdigest(),
                      "sha256": sha256(ZIP_PATH.read_bytes()).hexdigest(),
                      "tiles": 42, "font": f"{NAMESPACE}:tiles",
                      "contact_sheet": str(ROOT / "docs" / "tile-preview.png")}, indent=2))


if __name__ == "__main__":
    main()
