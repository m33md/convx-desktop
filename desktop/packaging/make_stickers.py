"""
Builds Disc Studio's sticker library from two Iconify JSON packages:

    npm pack @iconify-json/fluent-emoji-flat @iconify-json/mdi unicode-emoji-json
    python make_stickers.py <fluent-emoji-flat/package> <mdi/package> <unicode-emoji-json/package>

Writes desktop/src/main/resources/stickers/emoji.tsv.gz and icons.tsv.gz: one sticker a line,
"name<TAB>categories<TAB>art". An emoji's art is the SVG inside its 32x32 box; an icon's is its
path data (24x24, drawn in the sticker's colour), several paths split by "|".

Fluent Emoji (Microsoft, MIT): the default skin tone of each, as stickers are one colour each.
Material Design Icons (Pictogrammers, Apache 2.0): the categories that make sense on a disc,
without brand logos or deprecated icons.
"""
import gzip
import json
import os
import re
import sys

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "stickers")

EMOJI_CATEGORIES = [
    ("Smileys & Emotion", "Smileys"),
    ("People & Body", "People"),
    ("Animals & Nature", "Animals & nature"),
    ("Food & Drink", "Food & drink"),
    ("Activities", "Activities"),
    ("Travel & Places", "Travel & places"),
    ("Objects", "Objects"),
    ("Symbols", "Symbols"),
    ("Flags", "Flags"),
]

ICON_CATEGORIES = [
    ("Shape", "Shapes"),
    ("Music", "Music"),
    ("Audio", "Music"),
    ("Emoji", "Faces"),
    ("Nature", "Nature"),
    ("Animal", "Animals"),
    ("Weather", "Weather"),
    ("Holiday", "Holidays"),
    ("Drawing / Art", "Art"),
    ("Gaming / RPG", "Gaming"),
    ("Sport", "Sport"),
    ("Food / Drink", "Food"),
    ("Places", "Places"),
    ("Transportation + Flying", "Transport"),
    ("Transportation + Road", "Transport"),
    ("Transportation + Water", "Transport"),
    ("Transportation + Other", "Transport"),
    ("Clothing", "Clothing"),
    ("Health / Beauty", "Beauty"),
    ("Photography", "Photo & film"),
    ("Video / Movie", "Photo & film"),
    ("Science", "Science"),
    ("Religion", "Religion"),
    ("People / Family", "People"),
    ("Hardware / Tools", "Tools"),
    ("Arrow", "Arrows"),
    ("Alpha / Numeric", "Letters & numbers"),
    ("Math", "Math"),
    ("Date / Time", "Time"),
    ("Currency", "Money"),
]

TONES = re.compile(r"-(light|medium-light|medium|medium-dark|dark)(-|$)")


def load(package):
    with open(os.path.join(package, "icons.json"), encoding="utf-8") as f:
        icons = json.load(f)
    with open(os.path.join(package, "metadata.json"), encoding="utf-8") as f:
        categories = json.load(f)["categories"]
    return icons, categories


def write(name, rows):
    os.makedirs(OUT, exist_ok=True)
    data = "".join("\t".join(row) + "\n" for row in rows).encode("utf-8")
    with gzip.GzipFile(os.path.join(OUT, name), "wb", compresslevel=9, mtime=0) as f:
        f.write(data)
    print(name, len(rows), "stickers", len(data), "bytes")


def emoji(package, unicode_order):
    icons, categories = load(package)
    # Each category in Unicode's own order (grinning face first) rather than by name.
    with open(os.path.join(package, "chars.json"), encoding="utf-8") as f:
        chars = json.load(f)
    with open(os.path.join(unicode_order, "data-ordered-emoji.json"), encoding="utf-8") as f:
        ordered = json.load(f)
    place = {}
    for index, char in enumerate(ordered):
        codes = [format(ord(c), "x") for c in char]
        place.setdefault("-".join(codes), index)
        place.setdefault("-".join(c for c in codes if c != "fe0f"), index)
    order = {}
    for code, name in chars.items():
        key = [place.get(code, len(ordered))]
        if name not in order or key < order[name]:
            order[name] = key
    rows = []
    for source, label in EMOJI_CATEGORIES:
        for name in sorted(categories.get(source, []), key=lambda n: order.get(n, [0x10FFFF])):
            icon = icons["icons"].get(name)
            if icon is None or icon.get("hidden") or TONES.search(name):
                continue
            body = icon["body"]
            if "\t" in body or "\n" in body:
                body = re.sub(r"\s+", " ", body)
            rows.append((name, label, body))
    write("emoji.tsv.gz", rows)


def mdi(package):
    icons, categories = load(package)
    labels = {}
    for source, label in ICON_CATEGORIES:
        for name in categories.get(source, []):
            labels.setdefault(name, [])
            if label not in labels[name]:
                labels[name].append(label)
    order = []
    for _, label in ICON_CATEGORIES:
        if label not in order:
            order.append(label)
    rows = []
    path = re.compile(r'<path (?:fill="currentColor" d="([^"]*)"|d="([^"]*)" fill="currentColor")/>')
    # Grouped by their first category, in the order above, so the categories list in that order.
    for name, cats in sorted(labels.items(), key=lambda item: min(order.index(c) for c in item[1])):
        cats = sorted(cats, key=order.index)
        icon = icons["icons"].get(name)
        if icon is None or icon.get("hidden"):
            continue
        body = icon["body"]
        parts = path.findall(body)
        # Only icons made of plain filled paths, so each is one path in the sticker's colour.
        if not parts or path.sub("", body):
            continue
        rows.append((name, ",".join(cats), "|".join(a or b for a, b in parts)))
    write("icons.tsv.gz", rows)


if __name__ == "__main__":
    emoji(sys.argv[1], sys.argv[3])
    mdi(sys.argv[2])
