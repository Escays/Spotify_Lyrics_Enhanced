#!/usr/bin/env python3
"""Third probe round: fetch real lyric + translation payloads for a few songs."""
import json

from probe_platform_lyrics import show

NETEASE = [
    (35114127, "Shape of You / Ed Sheeran"),
    (657666, "残酷な天使のテーゼ / 高橋洋子"),
    (1330348068, "Lemon / 米津玄師"),
    (1824045033, "夜に駆ける / YOASOBI"),
]

QQ_MIDS = [
    ("0041gObR2QG98x", "Shape of You"),
    ("003cXP4n4TXFUp", "残酷な天使のテーゼ"),
]


def main():
    for song_id, label in NETEASE:
        url = f"https://music.163.com/api/song/lyric?id={song_id}&lv=-1&kv=-1&tv=-1"
        status, text = show(f"netease lyric {label}", url, {"Referer": "https://music.163.com/"}, limit=200)
        if status != 200:
            continue
        try:
            data = json.loads(text)
        except Exception as error:  # noqa: BLE001
            print("  parse failure:", error)
            continue
        print("  keys:", sorted(data.keys()))
        main_lrc = (data.get("lrc") or {}).get("lyric", "")
        trans = (data.get("tlyric") or {}).get("lyric", "")
        roma = (data.get("romalrc") or {}).get("lyric", "")
        print(f"  original lines={len(main_lrc.splitlines())} translated={len(trans.splitlines())} romanized={len(roma.splitlines())}")
        print("  original head:", main_lrc.splitlines()[:3])
        print("  translated head:", trans.splitlines()[:3] if trans else "(none)")
        print("  romanized head:", roma.splitlines()[:2] if roma else "(none)")

    for mid, label in QQ_MIDS:
        url = ("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + mid
               + "&format=json&nobase64=1&g_tk=5381&loginUin=0&hostUin=0&platform=yqq&needNewCode=0")
        status, text = show(f"qq lyric {label}", url, {"Referer": "https://y.qq.com/portal/player.html"}, limit=400)


if __name__ == "__main__":
    main()
