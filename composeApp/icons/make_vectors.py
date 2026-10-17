"""Convert the workspace's SVG icons into Android vector drawables.

Compose Multiplatform picks the renderer for a drawable by its file extension: `.xml` goes
through the multiplatform vector parser on every target, `.svg` goes through Skia on the
desktop and through nothing at all on Android, which has no SVG support in its resource
pipeline. An `.svg` in the package is therefore a hole where an icon should be on Android, so
the drawables are committed as Android vector XML.

The originals are the icons dropped into `assets/icons/` in the workspace root, outside this
repository, and this script is how the committed drawables are regenerated when one of them
changes. It understands the subset of SVG the icons use and **raises on anything else**, so it
cannot quietly emit a drawing that differs from the original.

Usage: python make_vectors.py <svg-directory> [output-directory] [size-dp]

The draughtsman's names for the assets are not the resources' names, so [NAMES] holds the
mapping; a file it does not mention keeps its own name. The output directory defaults to the
resource directory the drawables are committed in. `size-dp` is the size of a drawing that
declares no `width` and `height` of its own; it defaults to 24, the size the interface draws
icons at.

Two details of the format are worth knowing when reading the output:

* A view box that does not start at the origin is carried by a `<group>` that translates the
  drawing into the non-negative one the format requires. Material Symbols, for instance, are
  drawn in a box of `0 -960 960 960`: the drawing is *not* mirrored, so the group translates by
  960 rather than scaling by -1, which would turn every icon upside down.
* SVG class rules and inherited presentation attributes are resolved here, because vector
  drawables have neither: every `<path>` carries the fill, stroke and stroke geometry that
  were in effect for it. Neighbouring shapes that end up with the same paint are merged into
  one path, which is what the source's own grouping meant.
"""

import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# The name a drawing is dropped under, and the resource it becomes. A drawing that is not
# listed keeps its own name.
NAMES = {
    "2fa": "icon_2fa",
    "add": "icon_add",
    "image": "icon_image",
    "logo": "qualia2fa_logo",
    "menu": "icon_menu",
    "select": "icon_select",
    "select_all": "icon_select_all",
    "select_check_box": "icon_selected",
    "settings": "icon_settings",
    "sync": "icon_sync",
}

# Where the drawables are committed, relative to this script.
DEFAULT_OUTPUT = "../src/commonMain/composeResources/drawable"

# The size a drawing is declared at, where the number of its own coordinates is not a size on
# screen. The mark is written in a box of 900 units because that is how its coordinates are
# spelled, not because the interface ever shows it 900dp across: it is drawn at 96dp beside a
# title, at 32dp next to one, and as the window icon, so the resource declares 90dp.
SIZES = {
    "logo": 90.0,
}

# The resource the application mark becomes, and the two launcher layers generated from it.
MARK = "qualia2fa_logo"
LAUNCHER = "../src/androidMain/res/drawable/ic_launcher_foreground.xml"
LAUNCHER_MONOCHROME = "../src/androidMain/res/drawable/ic_launcher_monochrome.xml"

# The box an adaptive icon is drawn in, and the circle of it a launcher guarantees to show.
LAUNCHER_BOX = 108.0
LAUNCHER_SAFE = 66.0

# The styles a drawing can carry over from an ancestor. Everything else is rejected.
INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin")

# The default fill and stroke width of SVG, used when nothing in the file mentions them.
DEFAULT_FILL = "black"
DEFAULT_STROKE_WIDTH = "1"

# The size of a drawing that declares no size of its own.
DEFAULT_SIZE = 24

SHAPES = ("path", "line", "rect", "polygon", "polyline")
IGNORED = ("title", "desc", "style", "defs")

CLASS_RULE = re.compile(r"\.([A-Za-z_][\w-]*)\s*\{([^}]*)\}")
SEPARATORS = re.compile(r"[\s,]+")

# The named colours, as far as the icons use them; a name outside this table is an error
# rather than a guess.
NAMED_COLOURS = {
    "black": "000000",
    "white": "FFFFFF",
    "red": "FF0000",
    "green": "008000",
    "blue": "0000FF",
}


def local_name(element):
    """The element's name with its namespace stripped."""
    return element.tag.rsplit("}", 1)[-1]


def declarations(text):
    """The `key: value` pairs of a `style` attribute or of a CSS rule body."""
    result = {}
    for part in (text or "").split(";"):
        if ":" in part:
            key, value = part.split(":", 1)
            result[key.strip()] = value.strip()
    return result


def class_rules(root):
    """The class name to declarations map of the file's `<style>` blocks."""
    rules = {}
    for element in root.iter():
        if local_name(element) == "style":
            for name, body in CLASS_RULE.findall(element.text or ""):
                rules[name] = declarations(body)
    return rules


def style_of(element, inherited, rules):
    """The style in effect for an element, given the one it inherits.

    A presentation attribute is the weakest of the three sources and an inline `style` the
    strongest, which is the order SVG gives them.
    """
    style = dict(inherited)
    for name in INHERITED:
        if element.get(name) is not None:
            style[name] = element.get(name)
    for name in (element.get("class") or "").split():
        if name not in rules:
            raise ValueError(f"class {name!r} has no rule in this file")
        style.update(rules[name])
    style.update(declarations(element.get("style")))
    return style


def colour(value):
    """An SVG paint as `#AARRGGBB`, or `None` when there is nothing to paint with.

    `none` and an absent attribute both mean "not painted", which in a vector drawable is
    written by leaving the attribute out: an unpainted fill is the default there, and an
    unpainted stroke would otherwise carry a width and a cap into the file with it.
    """
    if value is None or value == "none":
        return None
    if value.startswith("url("):
        raise ValueError(f"paint server {value!r} is not supported")
    if value.startswith("#"):
        digits = value[1:]
        if len(digits) == 3:
            digits = "".join(character * 2 for character in digits)
        if len(digits) != 6:
            raise ValueError(f"colour {value!r} is neither #rgb nor #rrggbb")
        return "#FF" + digits.upper()
    if value in NAMED_COLOURS:
        return "#FF" + NAMED_COLOURS[value]
    raise ValueError(f"colour {value!r} is not a hex triplet or a known name")


def number(value):
    """A coordinate as short as it can be written without losing it."""
    text = f"{float(value):.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def point(x, y):
    """An `x,y` pair in path data."""
    return f"{number(x)},{number(y)}"


def path_data(element):
    """One SVG shape element as vector drawable path data."""
    name = local_name(element)
    if name == "path":
        data = element.get("d")
        if not data:
            raise ValueError("a path needs its `d`")
        return data
    if name == "line":
        return "M{}L{}".format(
            point(element.get("x1", 0), element.get("y1", 0)),
            point(element.get("x2", 0), element.get("y2", 0)),
        )
    if name in ("polygon", "polyline"):
        coordinates = SEPARATORS.split((element.get("points") or "").strip())
        if len(coordinates) % 2:
            raise ValueError("a polygon needs an even number of coordinates")
        pairs = [
            point(coordinates[index], coordinates[index + 1])
            for index in range(0, len(coordinates), 2)
        ]
        return "M" + "L".join(pairs) + ("Z" if name == "polygon" else "")
    if name == "rect":
        x, y = float(element.get("x", 0)), float(element.get("y", 0))
        width, height = float(element.get("width")), float(element.get("height"))
        rx = float(element.get("rx") or element.get("ry") or 0)
        ry = float(element.get("ry") or element.get("rx") or rx)
        if rx == 0 and ry == 0:
            return f"M{number(x)},{number(y)}H{number(x + width)}V{number(y + height)}H{number(x)}Z"
        return (
            f"M{point(x + rx, y)}"
            f"H{number(x + width - rx)}"
            f"A{number(rx)},{number(ry)} 0 0 1 {point(x + width, y + ry)}"
            f"V{number(y + height - ry)}"
            f"A{number(rx)},{number(ry)} 0 0 1 {point(x + width - rx, y + height)}"
            f"H{number(x + rx)}"
            f"A{number(rx)},{number(ry)} 0 0 1 {point(x, y + height - ry)}"
            f"V{number(y + ry)}"
            f"A{number(rx)},{number(ry)} 0 0 1 {point(x + rx, y)}Z"
        )
    raise ValueError(f"<{name}> is not a shape this converter understands")


def paint_of(style):
    """The attributes a shape is painted with, as a pair of a key and its values."""
    attributes = []
    fill = colour(style.get("fill", DEFAULT_FILL))
    stroke = colour(style.get("stroke"))
    if fill is not None:
        attributes.append(("fillColor", fill))
    if stroke is not None:
        attributes.append(("strokeColor", stroke))
        attributes.append(("strokeWidth", number(style.get("stroke-width", DEFAULT_STROKE_WIDTH))))
        if style.get("stroke-linecap"):
            attributes.append(("strokeLineCap", style["stroke-linecap"]))
        if style.get("stroke-linejoin"):
            attributes.append(("strokeLineJoin", style["stroke-linejoin"]))
    if not attributes:
        raise ValueError("a shape with neither a fill nor a stroke would not be drawn")
    return tuple(attributes)


def collect(element, inherited, rules, shapes):
    """Append `(paint, path data)` for every shape of a subtree, in document order."""
    style = style_of(element, inherited, rules)
    for child in element:
        name = local_name(child)
        if name == "g":
            collect(child, style, rules, shapes)
        elif name in SHAPES:
            shapes.append((paint_of(style_of(child, style, rules)), path_data(child)))
        elif name in IGNORED:
            continue
        else:
            raise ValueError(f"<{name}> is not an element this converter understands")


def path_elements(shapes):
    """The `<path>` elements for a shape list, merging those painted identically."""
    merged = []
    for paint, data in shapes:
        if merged and merged[-1][0] == paint:
            merged[-1][1].append(data)
        else:
            merged.append((paint, [data]))

    lines = []
    for paint, data in merged:
        lines.append("<path")
        lines.extend(f'    android:{name}="{value}"' for name, value in paint)
        lines.append('    android:pathData="{}" />'.format(" ".join(data)))
    return lines


def document(size, viewport_width, viewport_height, shapes, group=(), header=()):
    """One vector drawable as its XML text.

    [group] is the attributes of a `<group>` the paths are wrapped in, as `(name, value)`
    pairs; with none, the paths sit directly in the view port. [header] is a comment written
    above the drawing, as a sequence of lines.
    """
    lines = ['<?xml version="1.0" encoding="utf-8"?>']
    if header:
        lines.append("<!--")
        lines.extend(f"  {line}".rstrip() for line in header)
        lines.append("-->")
    lines.extend(
        [
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            f'    android:width="{number(size)}dp"',
            f'    android:height="{number(size)}dp"',
            f'    android:viewportWidth="{number(viewport_width)}"',
            f'    android:viewportHeight="{number(viewport_height)}">',
        ]
    )
    if group:
        lines.append("    <group")
        lines.extend(f'        android:{name}="{value}"' for name, value in group)
        lines[-1] += ">"

    indent = "        " if group else "    "
    lines.extend(indent + line for line in path_elements(shapes))

    if group:
        lines.append("    </group>")
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def write(target, text):
    """Writes one generated file, creating the directory it lives in."""
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="\n")


def convert(source, target, default_size, declared_size=None):
    """Convert one SVG file into a vector drawable at [target]."""
    root = ET.parse(source).getroot()
    if root.get("transform") is not None:
        raise ValueError("a transform on the root element is not supported")

    view_box = SEPARATORS.split((root.get("viewBox") or "").strip())
    if len(view_box) != 4:
        raise ValueError("the drawing needs a viewBox of four numbers")
    min_x, min_y, width, height = (float(value) for value in view_box)

    shapes = []
    collect(root, {}, class_rules(root), shapes)
    if not shapes:
        raise ValueError("the drawing has no shapes")

    size = (
        f"{number(declared_size)}dp"
        if declared_size is not None
        else (root.get("width") or root.get("height") or f"{number(default_size)}dp")
    )
    # A size in SVG is in user units, which are the pixels of the drawing and not the pixels
    # of a screen: Android's own unit for a drawable's intrinsic size is the dp, so `24px`
    # and a bare `24` both become `24dp` and the icon keeps its size at every density.
    match = re.fullmatch(r"([\d.]+)(px|dp)?", size)
    if not match:
        raise ValueError(f"size {size!r} is not a number of user units")

    # A view box that does not start at the origin is carried by a group, and the group is a
    # translation rather than a mirror: the drawing keeps its orientation and only its
    # coordinates move into the range the format allows.
    group = []
    if min_x:
        group.append(("translateX", number(-min_x)))
    if min_y:
        group.append(("translateY", number(-min_y)))

    write(target, document(float(match.group(1)), width, height, shapes, group))
    return shapes, width


def content_radius(shapes, viewport):
    """How far the drawing's line work reaches from its centre, in the drawing's own units.

    Only the **stroked** shapes are measured: a filled plate covers the whole box by
    definition and says nothing about where the drawing ends. Their coordinates are read in
    pairs, which is what the commands a line drawing is made of - `M`, `L` and `C` - all take;
    a shape written with `H`, `V` or an arc would not read as pairs, and the conversion fails
    rather than scaling the drawing to a distance that is not its own.

    A curve is measured at its control points, which lie on or outside it, so the circle this
    returns is never smaller than the drawing and the launcher icon is never scaled up past
    what a mask shows.
    """
    centre = viewport / 2
    far = 0.0
    for paint, data in shapes:
        if not any(name == "strokeColor" for name, _ in paint):
            continue
        numbers = [float(value) for value in re.findall(r"-?\d+(?:\.\d+)?", data)]
        if len(numbers) % 2:
            raise ValueError(
                f"the mark's line work ({data[:24]}...) is not written as x,y pairs, so the "
                "launcher icon cannot be scaled to it"
            )
        for x, y in zip(numbers[::2], numbers[1::2]):
            far = max(far, math.hypot(x - centre, y - centre))
    if far == 0.0:
        raise ValueError("the mark has no stroked shape to measure")
    return far


def launcher_shapes(shapes):
    """The shapes as the launcher icon draws them: the line work in one weight.

    The mark draws its frame heavier than its letters, and that reads as intended when it is
    drawn a hundred pixels across and more. A launcher draws the icon as small as 48 pixels,
    where the letters are two or three pixels wide and a lighter stroke stops being a lighter
    stroke and becomes grey - the drawing looks out of focus rather than delicate. The launcher
    layers therefore draw every stroke at the heaviest width the mark itself uses, so each part
    of the mark survives the smallest size a launcher puts it at. The drawing in the interface
    keeps the two weights.
    """
    widths = [float(dict(paint)["strokeWidth"]) for paint, _ in shapes if "strokeWidth" in dict(paint)]
    if not widths:
        return shapes
    heaviest = max(widths)
    return [
        (
            tuple(
                ("strokeWidth", number(heaviest)) if name == "strokeWidth" else (name, value)
                for name, value in paint
            ),
            data,
        )
        for paint, data in shapes
    ]


def launcher_layers(shapes, viewport, foreground, monochrome, source):
    """Write the mark as the two layers of the adaptive launcher icon.

    A launcher draws those layers in a box of 108 units and is only obliged to show the middle
    66 of them - a circle, in effect - so the drawing is scaled until its farthest point sits
    on that circle and is then centred in the box. Scaling it to the 72-unit **square** instead
    leaves the corners of the drawing outside the circle, and a launcher whose mask rounds them
    off cuts them: the icon then reads as a drawing running off its own tile.

    The foreground keeps the mark's plate, which is the colour of the background layer and
    therefore invisible on it; the monochrome layer is the line work alone, because a themed
    launcher supplies the background and tints whatever alpha it is given.
    """
    scale = LAUNCHER_SAFE / (2 * content_radius(shapes, viewport))
    offset = (LAUNCHER_BOX - viewport * scale) / 2
    group = (
        ("scaleX", number(scale)),
        ("scaleY", number(scale)),
        ("translateX", number(offset)),
        ("translateY", number(offset)),
    )
    circle = 2 * content_radius(shapes, viewport)
    drawn = launcher_shapes(shapes)
    line_work = [shape for shape in drawn if any(name == "strokeColor" for name, _ in shape[0])]
    generated = (
        f"Generated by icons/make_vectors.py from {source.name}; edit that file and run the",
        "script rather than editing this one.",
    )

    write(
        foreground,
        document(
            LAUNCHER_BOX,
            LAUNCHER_BOX,
            LAUNCHER_BOX,
            drawn,
            group,
            header=generated
            + (
                "",
                "The mark on the layer the launcher masks. A launcher draws this in a box of 108",
                f"units and is only obliged to show the middle {LAUNCHER_SAFE:g} of them, and the",
                "farthest thing in the drawing from its own centre is the corner of the frame,",
                f"{content_radius(shapes, viewport):g} of the drawing's {viewport:g} units away. The",
                f"drawing is therefore scaled by {LAUNCHER_SAFE:g}/{circle:g} and centred, which puts",
                "the whole mark inside that circle. Scaling it to the 72-unit square instead is",
                "what leaves the frame's corners outside the circle, where a mask with round",
                "corners cuts them off and the icon reads as a drawing running off its own tile.",
                "",
                "Every stroke is drawn at the heaviest width the mark uses: at the sizes a",
                "launcher draws this, a lighter stroke is not a lighter stroke but a grey one.",
                "The plate is part of the drawing and is the same colour as the background layer,",
                "so on a masked launcher the two are one surface.",
            ),
        ),
    )
    write(
        monochrome,
        document(
            LAUNCHER_BOX,
            LAUNCHER_BOX,
            LAUNCHER_BOX,
            line_work,
            group,
            header=generated
            + (
                "",
                "The mark as a themed launcher draws it. Only the line work is here: the launcher",
                "supplies the background and tints whatever alpha it is given, so the plate would",
                "become a filled square. The geometry is the foreground's.",
            ),
        ),
    )


def main():
    if not 2 <= len(sys.argv) <= 4:
        raise SystemExit(
            "usage: python make_vectors.py <svg-directory> [output-directory] [size-dp]"
        )

    source = Path(sys.argv[1])
    target = (
        Path(sys.argv[2])
        if len(sys.argv) >= 3
        else (Path(__file__).parent / DEFAULT_OUTPUT).resolve()
    )
    default_size = float(sys.argv[3]) if len(sys.argv) == 4 else DEFAULT_SIZE
    drawings = sorted(source.glob("*.svg"))
    if not drawings:
        raise SystemExit(f"{source} holds no SVG to convert")

    for drawing in drawings:
        name = NAMES.get(drawing.stem, drawing.stem)
        shapes, viewport = convert(
            drawing, target / f"{name}.xml", default_size, SIZES.get(drawing.stem)
        )
        print(f"{drawing.name} -> {name}.xml: {len(shapes)} shape(s)")
        if name == MARK:
            here = Path(__file__).parent
            launcher_layers(shapes, viewport, here / LAUNCHER, here / LAUNCHER_MONOCHROME, drawing)
            print(
                f"    and {Path(LAUNCHER).name} with {Path(LAUNCHER_MONOCHROME).name}, the mark "
                f"on the {LAUNCHER_SAFE:g}-unit circle a launcher shows"
            )


if __name__ == "__main__":
    main()
