#!/usr/bin/env python3
"""Fourth probe round: QQ lyric translation coverage via musicu GetPlayLyricInfo."""
import base64
import json
import urllib.parse

from probe_platform_lyrics import fetch

SONGS = [
    ("Shape of You", "Ed Sheeran"),
    ("Faded", "Alan Walker"),
    ("Yesterday Once More", "Carpenters"),
    ("残酷な天使のテーゼ", "高橋洋子"),
    ("Lemon", "米津玄師"),
    ("夜に駆ける", "YOASOBI"),
    ("Hallelujah", "Jeff Buckley"),
]


def qq_search(title, artist):
    key = urllib.parse.quote(f"{title} {artist}")
    url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w=" + key + "&format=json"
    status, raw = fetch(url, {"Referer": "https://y.qq.com/"})
    if status != 200:
        return []
    data = json.loads(raw.decode("utf-8", "replace"))
    songs = data.get("data", {}).get("song", {}).get("list", [])
    return [(s.get("mid") or s.get("songmid"), s.get("name") or s.get("songname"),
             "/".join(x["name"] for x in s.get("singer", [])),
             s.get("interval"), s.get("albumname") or s.get("albumname")) for s in songs]


def qq_lyric(mid, song_id=0):
    body = {"req_1": {"module": "music.musichallSong.PlayLyricInfo", "method": "GetPlayLyricInfo",
                      "param": {"songMID": mid, "songID": song_id, "trans": 1, "roma": 0, "type": 1}}}
    status, raw = fetch("https://u.y.qq.com/cgi-bin/musicu.fcg",
                        {"Referer": "https://y.qq.com/", "Content-Type": "application/json"},
                        "POST", json.dumps(body))
    if status != 200:
        return None
    data = json.loads(raw.decode("utf-8", "replace")).get("req_1", {}).get("data", {})
    def decode64(field):
        value = data.get(field) or ""
        if not value:
            return ""
        try:
            return base64.b64decode(value).decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            return value
    return {"lyric": decode64("lyric"), "trans": decode64("trans"), "roma": decode64("roma"),
            "hasMultiTrans": data.get("hasMultiTrans")}


def useful_translation(text):
    """A usable Chinese translation ignores '//' placeholders and empty rows."""
    lines = []
    for line in text.splitlines():
        body = line.split("]", 1)[1].strip() if line.startswith("[") and "]" in line else line.strip()
        if body and body != "//":
            lines.append(body)
    return lines


def main():
    for title, artist in SONGS:
        print(f"\n########## {title} / {artist} ##########")
        hits = qq_search(title, artist)
        print("qq search hits:", hits[:3])
        for mid, name, singers, interval, album in hits[:2]:
            payload = qq_lyric(mid)
            if payload is None:
                print("  lyric fetch failed")
                continue
            trans = useful_translation(payload["trans"])
            original = useful_translation(payload["lyric"])
            print(f"  {mid} {name} / {singers} / {interval}s / {album}")
            print(f"    original={len(original)} translation={len(trans)} hasMultiTrans={payload['hasMultiTrans']}")
            print("    trans head:", trans[:3])


if __name__ == "__main__":
    main()
