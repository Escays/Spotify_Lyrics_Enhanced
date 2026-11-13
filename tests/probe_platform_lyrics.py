#!/usr/bin/env python3
"""Probe live NetEase / QQ / Genius endpoints and print what actually came back.

This is a manual network check, not a unit test: it never fabricates data and
prints the raw status for every service so a failure is reported, not hidden.
"""
import json
import socket
import ssl
import sys
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36 Edg/152.0.0.0")
TIMEOUT = 20


def fetch(url, headers=None, method="GET", body=None):
    request = urllib.request.Request(url, method=method)
    request.add_header("User-Agent", UA)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    data = body.encode("utf-8") if body else None
    try:
        with urllib.request.urlopen(request, data=data, timeout=TIMEOUT, context=ssl.create_default_context()) as response:
            raw = response.read()
            return response.status, raw
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except (urllib.error.URLError, socket.timeout, ssl.SSLError) as error:
        return -1, str(error).encode()


def show(name, url, headers=None, method="GET", body=None, limit=1600):
    status, raw = fetch(url, headers, method, body)
    text = raw.decode("utf-8", "replace")
    print(f"\n=== {name} ===\n{method} {url}\nstatus={status} bytes={len(raw)}")
    print(text[:limit].replace("\n", " ")[:limit])
    return status, text


QUERIES = [
    ("English pop", "Shape of You", "Ed Sheeran"),
    ("Japanese character song", "残酷な天使のテーゼ", "高橋洋子"),
    ("Live version", "Hallelujah Live", "Jeff Buckley"),
]


def netease_search(title, artist):
    keyword = urllib.parse.quote(f"{title} {artist}")
    url = ("https://music.163.com/api/search/get/web?s=" + keyword
           + "&type=1&offset=0&total=true&limit=5")
    return url, {"Referer": "https://music.163.com/", "Accept": "application/json"}


def qq_search(title, artist):
    keyword = urllib.parse.quote(f"{title} {artist}")
    url = ("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?ct=24&qqmusic_ver=1298"
           "&new_json=1&remoteplace=txt.yqq.song&t=0&aggr=1&cr=1&catZhida=1&lossless=0"
           "&flag_qc=0&p=1&n=5&w=" + keyword + "&format=json")
    return url, {"Referer": "https://y.qq.com/", "Accept": "application/json"}


def main():
    for label, title, artist in QUERIES:
        print(f"\n########## {label}: {title} / {artist} ##########")
        url, headers = netease_search(title, artist)
        status, text = show("netease search", url, headers)
        song_id = None
        if status == 200:
            try:
                songs = json.loads(text)["result"]["songs"]
                print("netease hits:", [(s["id"], s["name"], "/".join(a["name"] for a in s.get("artists", [])),
                                         round(s.get("duration", 0) / 1000)) for s in songs])
                song_id = songs[0]["id"] if songs else None
            except Exception as error:  # noqa: BLE001 - probe prints the real failure
                print("netease parse failure:", error)
        if song_id:
            show("netease lyric", f"https://music.163.com/api/song/lyric?id={song_id}&lv=-1&kv=-1&tv=-1",
                 {"Referer": "https://music.163.com/"}, limit=900)

        url, headers = qq_search(title, artist)
        status, text = show("qq search", url, headers)
        mid = None
        if status == 200:
            try:
                payload = text[text.index("{"):text.rindex("}") + 1]
                songs = json.loads(payload)["data"]["song"]["list"]
                print("qq hits:", [(s["mid"], s["name"], "/".join(a["singer"] and [x["name"] for x in s["singer"]]),
                                    s.get("interval")) for s in songs])
                mid = songs[0]["mid"] if songs else None
            except Exception as error:  # noqa: BLE001
                print("qq parse failure:", error)
        if mid:
            show("qq lyric", "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                 + mid + "&format=json&nobase64=0&g_tk=5381",
                 {"Referer": "https://y.qq.com/portal/player.html"}, limit=900)

        keyword = urllib.parse.quote(f"{title} {artist}")
        status, text = show("genius search", f"https://genius.com/api/search/multi?q={keyword}",
                            {"Accept": "application/json"}, limit=700)
        if status == 200:
            try:
                sections = json.loads(text)["response"]["sections"]
                hits = []
                for section in sections:
                    for hit in section.get("hits", []):
                        result = hit.get("result", {})
                        if result.get("id"):
                            hits.append((result.get("id"), result.get("title"), result.get("full_title")))
                print("genius hits:", hits[:5])
                if hits:
                    song_id = hits[0][0]
                    show("genius song", f"https://genius.com/api/songs/{song_id}?text_format=plain",
                         {"Accept": "application/json"}, limit=1200)
                    show("genius referents", f"https://genius.com/api/referents?song_id={song_id}&text_format=plain&per_page=5",
                         {"Accept": "application/json"}, limit=700)
            except Exception as error:  # noqa: BLE001
                print("genius parse failure:", error)


if __name__ == "__main__":
    sys.exit(main())
