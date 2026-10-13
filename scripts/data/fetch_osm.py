"""Fetch OpenStreetMap features around the Harbin scenic sites and save them as GeoJSON.

Data (c) OpenStreetMap contributors, ODbL 1.0. https://www.openstreetmap.org/copyright
Coordinates are WGS84 (EPSG:4326).

Usage: python -I scripts/data/fetch_osm.py [output_dir]
"""
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

MIRRORS = [
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass-api.de/api/interpreter",
]
UA = "snow-world-dataprep/0.1"
# south, west, north, east: Sun Island, Harbin Ice-Snow World, Central Street and the Songhua river between them
BBOX = (45.755, 126.540, 45.815, 126.660)

LAYERS = {
    "areas": """
        nwr["leisure"~"park|garden|nature_reserve"]({bbox});
        nwr["tourism"~"theme_park|attraction|zoo"]({bbox});
        nwr["place"="island"]({bbox});
    """,
    "water": """
        nwr["natural"="water"]({bbox});
        nwr["waterway"="riverbank"]({bbox});
        way["waterway"="river"]({bbox});
    """,
    "roads": """
        way["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|pedestrian|living_street)$"]({bbox});
        way["railway"="rail"]({bbox});
    """,
    "buildings": """
        way["building"]({bbox});
    """,
    "pois": """
        nwr["tourism"~"hotel|hostel|guest_house|museum|attraction|viewpoint|artwork|information"]({bbox});
        nwr["amenity"~"restaurant|cafe|fast_food|bar|parking|toilets|ticket_office|police|clinic|first_aid"]({bbox});
        nwr["shop"]({bbox});
        nwr["public_transport"="station"]({bbox});
    """,
}

KEEP_TAGS = {
    "name", "name:zh", "name:en", "tourism", "leisure", "amenity", "shop", "place", "natural",
    "waterway", "highway", "railway", "building", "building:levels", "height", "cuisine", "stars",
}


def overpass(body: str) -> dict:
    query = f"[out:json][timeout:180];({body});out geom tags;"
    data = urllib.parse.urlencode({"data": query}).encode()
    last = None
    for attempt in range(3):
        for url in MIRRORS:
            try:
                req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=240) as resp:
                    raw = resp.read()
                if raw.lstrip().startswith(b"{"):
                    return json.loads(raw)
                last = raw[:300]
            except Exception as exc:  # network errors: try next mirror
                last = exc
        time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"overpass failed: {last}")


def ring(geom: list) -> list:
    pts = [[round(p["lon"], 7), round(p["lat"], 7)] for p in geom if p]
    if pts and pts[0] != pts[-1]:
        pts.append(pts[0])
    return pts


def to_feature(el: dict):
    tags = {k: v for k, v in el.get("tags", {}).items() if k in KEEP_TAGS}
    props = {"osm_type": el["type"], "osm_id": el["id"], **tags}
    if el["type"] == "node":
        geometry = {"type": "Point", "coordinates": [el["lon"], el["lat"]]}
    elif el["type"] == "way":
        geom = el.get("geometry") or []
        if len(geom) < 2:
            return None
        closed = geom[0] == geom[-1]
        areaish = closed and not ("highway" in tags and tags.get("highway") != "pedestrian") \
            and "railway" not in tags and tags.get("waterway") != "river"
        if areaish and len(geom) >= 4:
            geometry = {"type": "Polygon", "coordinates": [ring(geom)]}
        else:
            geometry = {"type": "LineString", "coordinates": [[p["lon"], p["lat"]] for p in geom]}
    elif el["type"] == "relation":
        outers = [m for m in el.get("members", []) if m.get("role") == "outer" and m.get("geometry")]
        polys = [[ring(m["geometry"])] for m in outers if len(m["geometry"]) >= 4 and m["geometry"][0] == m["geometry"][-1]]
        if polys:
            geometry = {"type": "MultiPolygon", "coordinates": polys}
        elif "bounds" in el:
            b = el["bounds"]
            geometry = {"type": "Point", "coordinates": [(b["minlon"] + b["maxlon"]) / 2, (b["minlat"] + b["maxlat"]) / 2]}
        else:
            return None
    else:
        return None
    return {"type": "Feature", "properties": props, "geometry": geometry}


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "data/osm")
    out.mkdir(parents=True, exist_ok=True)
    bbox = ",".join(str(v) for v in BBOX)
    for name, body in LAYERS.items():
        result = overpass(body.format(bbox=bbox))
        feats = [f for f in (to_feature(e) for e in result.get("elements", [])) if f]
        fc = {
            "type": "FeatureCollection",
            "attribution": "(c) OpenStreetMap contributors, ODbL 1.0",
            "osm_base": result.get("osm3s", {}).get("timestamp_osm_base"),
            "bbox": [BBOX[1], BBOX[0], BBOX[3], BBOX[2]],
            "features": feats,
        }
        (out / f"{name}.geojson").write_text(json.dumps(fc, ensure_ascii=False), encoding="utf-8")
        print(f"{name}: {len(feats)} features")
        time.sleep(3)


if __name__ == "__main__":
    main()
