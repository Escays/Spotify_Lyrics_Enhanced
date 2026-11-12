#!/usr/bin/env python3
"""Check what Kugou's public search returns for a few queries."""
import json
import urllib.parse

from probe_platform_lyrics import fetch

QUERIES = ["Supper's Ready Genesis", "Supper's Ready", "Sound and Vision David Bowie", "Lemon 米津玄師"]

for query in QUERIES:
    url = ("http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword="
           + urllib.parse.quote(query) + "&page=1&pagesize=5&showtype=1")
    status, raw = fetch(url)
    try:
        data = json.loads(raw.decode("utf-8", "replace"))
        info = data.get("data", {}).get("info", [])
        print(query, "->", status, len(info),
              [(item.get("songname"), item.get("singername"), item.get("duration")) for item in info[:3]])
    except Exception as error:  # noqa: BLE001 - probe prints the real failure
        print(query, "ERR", status, repr(error), raw[:120])
