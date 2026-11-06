"""
Draws the app's glyphs from the SF Symbols for Compose pack (Jetpack_SF_Symbols, MIT), the same symbols
the phone shows. Each symbol's monochrome outline is fitted into a 24x24 Android vector, centred,
under the app's own icon name, so every ConvxIcon of that name draws it.

    git clone --depth=1 https://github.com/cosmictaserdev-creator/Jetpack_SF_Symbols /tmp/sf
    python3 desktop/packaging/make_sf_icons.py /tmp/sf

The symbols are Apple's designs; the pack's licence covers its code, not the glyphs.
"""
import os
import re
import sys

# The app's icon name -> the SF Symbol drawn for it. Brand marks (Spotify, Discord, Convx) and the
# player's own Apple-style glyphs keep their drawings.
SYMBOLS = {
    "account": "person.crop.circle",
    "add": "plus",
    "add_circle": "plus.circle.fill",
    "album": "square.stack",
    "arrow_back": "arrow.left",
    "arrow_back_ios": "chevron.left",
    "arrow_forward": "arrow.right",
    "arrow_top_left": "arrow.up.left",
    "artist": "music.microphone",
    "backup": "externaldrive.fill",
    "bug_report": "ladybug",
    "cached": "internaldrive.fill",
    "cast": "airplay.audio",
    "chat_msg": "bubble.left.and.bubble.right",
    "check": "checkmark",
    "chevron_leftpx": "chevron.left",
    "chevron_right_px": "chevron.right",
    "chevron_up_down": "chevron.up.chevron.down",
    "close": "xmark",
    "cloud": "cloud.fill",
    "content_copy": "document.on.document",
    "delete": "trash",
    "download": "arrow.down.circle",
    "drag_handle": "line.3.horizontal",
    "edit": "pencil",
    "equalizer": "slider.vertical.3",
    "error": "exclamationmark.circle.fill",
    "expand_less": "chevron.up",
    "expand_more": "chevron.down",
    "explicit": "e.square.fill",
    "explore_outlined": "square.grid.2x2",
    "fast_forward": "forward.fill",
    "favorite": "heart.fill",
    "favorite_border": "heart",
    "fullscreen": "arrow.up.left.and.arrow.down.right",
    "globe": "globe",
    "graphic_eq": "waveform",
    "grid_view": "square.grid.2x2",
    "group": "person.2.fill",
    "hide_image": "eye.slash",
    "history": "clock.arrow.trianglehead.counterclockwise.rotate.90",
    "home_filled": "house.fill",
    "home_outlined": "house",
    "ic_push_pin": "pin.fill",
    "ic_queue_infinity": "infinity",
    "image": "photo",
    "info": "info.circle",
    "ios_share": "square.and.arrow.up",
    "key": "key.fill",
    "language": "globe",
    "library_add": "plus.circle",
    "library_add_check": "checkmark.circle.fill",
    "link": "link",
    "list": "list.bullet",
    "lock": "lock.fill",
    "logout": "rectangle.portrait.and.arrow.right",
    "lyrics": "quote.bubble",
    "mic": "microphone.fill",
    "more_horiz": "ellipsis",
    "more_vert": "ellipsis",
    "music_history": "clock.arrow.trianglehead.counterclockwise.rotate.90",
    "music_note": "music.note",
    "offline": "wifi.slash",
    "palette": "paintpalette",
    "pause": "pause.fill",
    "person": "person.fill",
    "play": "play.fill",
    "playlist_add": "text.badge.plus",
    "playlist_play": "music.note.list",
    "queue_music": "music.note.list",
    "radio": "dot.radiowaves.left.and.right",
    "refresh": "arrow.clockwise",
    "remove": "minus",
    "repeat": "repeat",
    "repeat_one": "repeat.1",
    "replay": "arrow.counterclockwise",
    "restore": "clock.arrow.trianglehead.counterclockwise.rotate.90",
    "right_panel": "sidebar.right",
    "search": "magnifyingglass",
    "security": "lock.shield.fill",
    "settings": "gearshape.fill",
    "sf_dot_radiowaves": "dot.radiowaves.left.and.right",
    "sf_house_fill": "house.fill",
    "sf_music_note_square_stack": "music.note.square.stack.fill",
    "sf_square_grid_fill": "square.grid.2x2.fill",
    "share": "square.and.arrow.up",
    "shuffle": "shuffle",
    "sidebar": "sidebar.left",
    "skip_next": "forward.end.fill",
    "skip_previous": "backward.end.fill",
    "sleep_timer": "moon.zzz.fill",
    "sliders": "slider.horizontal.3",
    "sparks": "sparkles",
    "speed": "gauge.with.needle",
    "star": "star",
    "star_fill": "star.fill",
    "stats": "chart.bar.fill",
    "storage": "folder.fill",
    "swipe": "hand.draw",
    "sync": "arrow.trianglehead.2.clockwise.rotate.90",
    "thumb_up_like": "hand.thumbsup.fill",
    "timer": "timer",
    "translate": "character.bubble",
    "trending_up": "chart.line.uptrend.xyaxis",
    "tune": "slider.horizontal.3",
    "volume_down": "speaker.wave.1.fill",
    "volume_mute": "speaker.fill",
    "volume_off": "speaker.slash.fill",
    "volume_up": "speaker.wave.2.fill",
    "warning": "exclamationmark.triangle.fill",
    "wifi_proxy": "network",
}

# The glyph's longer side, of the 24 the icon is, leaving 2 clear on each side.
FIT = 20.0
NUM = re.compile(r"-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?")


def kotlin_name(symbol):
    parts = []
    for part in symbol.split("."):
        parts.append(part[:1].upper() + part[1:])
    return "SF" + "".join(parts)


def load(pack, symbol):
    path = os.path.join(pack, "sfsymbols/src/commonMain/kotlin/composables/sfsymbols/monochrome", kotlin_name(symbol) + ".kt")
    with open(path, encoding="utf-8") as f:
        text = f.read()
    w = float(re.search(r"viewportWidth = ([\d.]+)f", text).group(1))
    h = float(re.search(r"viewportHeight = ([\d.]+)f", text).group(1))
    paths = re.findall(r'addSfPath\("([^"]+)"', text)
    return w, h, paths


def bounds(paths):
    xs, ys = [], []
    for d in paths:
        nums = [float(n) for n in NUM.findall(d)]
        xs += nums[0::2]
        ys += nums[1::2]
    return min(xs), min(ys), max(xs), max(ys)


def transform(d, scale, dx, dy):
    """The pack's outlines are absolute M, L, C and Z only, so every pair is a point."""
    out = []
    for token in re.findall(r"[MLCZ]|" + NUM.pattern, d):
        out.append(token)
    result, pair = [], []
    for token in out:
        if token in "MLCZ":
            result.append(token)
            continue
        pair.append(float(token))
        if len(pair) == 2:
            x, y = pair[0] * scale + dx, pair[1] * scale + dy
            result.append(f"{x:.3f}".rstrip("0").rstrip(".") + "," + f"{y:.3f}".rstrip("0").rstrip("."))
            pair = []
    text = ""
    for token in result:
        if token in "MLCZ":
            text += token
        else:
            text += token if text[-1:] in "MLCZ" else " " + token
    return text


def main():
    pack = sys.argv[1]
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src/main/resources/icons")
    for name, symbol in sorted(SYMBOLS.items()):
        _, _, paths = load(pack, symbol)
        x0, y0, x1, y1 = bounds(paths)
        scale = FIT / max(x1 - x0, y1 - y0)
        dx = 12 - (x0 + x1) / 2 * scale
        dy = 12 - (y0 + y1) / 2 * scale
        body = "".join(
            f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{transform(d, scale, dx, dy)}" />\n'
            for d in paths
        )
        with open(os.path.join(out_dir, name + ".xml"), "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                f"<!-- SF Symbol {symbol}, from Jetpack_SF_Symbols (MIT); drawn by make_sf_icons.py. -->\n"
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="24dp"\n    android:height="24dp"\n'
                '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
                + body + "</vector>\n"
            )
    print(f"{len(SYMBOLS)} icons written")


if __name__ == "__main__":
    main()
