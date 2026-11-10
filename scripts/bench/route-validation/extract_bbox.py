"""Extract the ways (and their nodes) inside a bbox from an OSM PBF.

Two passes: the first keeps every way with at least one node in the box and
records the node refs those ways need; the second writes the kept nodes and
ways. Relations are dropped, which is enough for a routing graph import.
"""

import sys

import osmium

WEST, SOUTH, EAST, NORTH = (float(x) for x in sys.argv[2].split(","))
SOURCE = sys.argv[1]
TARGET = sys.argv[3]


class Collect(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.way_ids = set()
        self.node_ids = set()

    def way(self, w):
        keep = False
        for node in w.nodes:
            location = node.location
            if not location.valid():
                continue
            if WEST <= location.lon <= EAST and SOUTH <= location.lat <= NORTH:
                keep = True
                break
        if not keep:
            return
        self.way_ids.add(w.id)
        for node in w.nodes:
            self.node_ids.add(node.ref)


collector = Collect()
osm = osmium.FileProcessor(SOURCE).with_locations()
osm.with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
for obj in osm:
    collector.way(obj)
print(f"ways {len(collector.way_ids)} nodes {len(collector.node_ids)}", file=sys.stderr)

writer = osmium.SimpleWriter(TARGET, osmium.osm.ALL)


class Write(osmium.SimpleHandler):
    def node(self, n):
        if n.id in collector.node_ids:
            writer.add_node(n)

    def way(self, w):
        if w.id in collector.way_ids:
            writer.add_way(w)


Write().apply_file(SOURCE)
writer.close()
print("done", file=sys.stderr)
