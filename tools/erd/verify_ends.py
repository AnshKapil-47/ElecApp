"""Check every edge in a Graphviz ERD SVG: crow's-foot marks must touch the CHILD table.

Usage: python3 verify_ends.py file.svg
Prints one line per edge and exits 1 if any crow mark sits on the parent end.
"""
import re
import sys
import xml.etree.ElementTree as ET

NS = {"s": "http://www.w3.org/2000/svg"}
MANY_PARENT_OK = set()  # no edge has "many" on the parent end


def pts(s):
    return [tuple(map(float, p.split(","))) for p in s.strip().split()]


def bbox(points):
    xs = [p[0] for p in points]; ys = [p[1] for p in points]
    return min(xs), min(ys), max(xs), max(ys)


def dist_to_box(p, b):
    x, y = p
    dx = max(b[0] - x, 0, x - b[2]); dy = max(b[1] - y, 0, y - b[3])
    return (dx * dx + dy * dy) ** 0.5


def wrong_edges(path):
    """Return list of (parent, child) whose crow mark touches the parent."""
    import io, contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        try:
            main(path)
        except SystemExit:
            pass
    out = []
    for line in buf.getvalue().splitlines():
        if line.startswith("WRONG"):
            p, c = line.split()[1], line.split()[3].rstrip(":")
            out.append((p, c))
    return out


def main(path):
    root = ET.parse(path).getroot()
    boxes = {}
    for g in root.iter("{http://www.w3.org/2000/svg}g"):
        if g.get("class") == "node":
            name = g.find("s:title", NS).text
            polys = [pts(p.get("points")) for p in g.findall("s:polygon", NS)]
            allp = [q for p in polys for q in p]
            boxes[name] = bbox(allp)
    bad = 0
    for g in root.iter("{http://www.w3.org/2000/svg}g"):
        if g.get("class") != "edge":
            continue
        title = g.find("s:title", NS).text.replace("&#45;", "-")
        parent, child = title.split("->")
        crows = []
        for poly in g.findall("s:polygon", NS):
            p = pts(poly.get("points"))
            if len(p) >= 9:  # crow's foot polygon (tee bars have 4-5 points)
                crows.append(p)
        verdicts = []
        for c in crows:
            # the toes of the crow are the points that touch a table border
            near_child = min(dist_to_box(q, boxes[child]) for q in c)
            near_parent = min(dist_to_box(q, boxes[parent]) for q in c)
            if parent == child:
                verdicts.append("self")
            else:
                verdicts.append("child" if near_child < near_parent else "PARENT")
        status = "OK" if all(v in ("child", "self") for v in verdicts) else "WRONG"
        if status == "WRONG":
            bad += 1
        print(f"{status:5} {parent} -> {child}: crow at {verdicts or ['none']}")
    print(f"{bad} wrong")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main(sys.argv[1])
