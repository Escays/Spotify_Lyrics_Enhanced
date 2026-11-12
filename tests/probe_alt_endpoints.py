#!/usr/bin/env python3
"""Second probe round: alternate NetEase / QQ endpoints."""
import json
import urllib.parse

from probe_platform_lyrics import show, fetch, UA

Q = [("Shape of You", "Ed Sheeran"), ("残酷な天使のテーゼ", "高橋洋子")]


def netease_variants(title, artist):
    s = urllib.parse.quote(f"{title} {artist}")
    return [
        ("cloudsearch pc", f"https://music.163.com/api/cloudsearch/pc?s={s}&type=1&limit=5&offset=0",
         {"Referer": "https://music.163.com/"}),
        ("search pc", f"https://music.163.com/api/search/pc?s={s}&type=1&limit=5&offset=0",
         {"Referer": "https://music.163.com/"}),
        ("search get", f"https://music.163.com/api/search/get?s={s}&type=1&limit=5&offset=0",
         {"Referer": "https://music.163.com/"}),
        ("interface search", f"https://music.163.com/eapi/search/get?s={s}&type=1&limit=5&offset=0",
         {"Referer": "https://music.163.com/"}),
    ]


def qq_variants(title, artist):
    key = urllib.parse.quote(f"{title} {artist}")
    return [
        ("smartbox", f"https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key={key}&format=json&g_tk=5381",
         {"Referer": "https://y.qq.com/"}),
        ("client_search old", "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w=" + key + "&format=json",
         {"Referer": "https://y.qq.com/"}),
        ("musicu", "https://u.y.qq.com/cgi-bin/musicu.fcg", None),
    ]


def main():
    for title, artist in Q:
        print(f"\n########## {title} / {artist} ##########")
        for name, url, headers in netease_variants(title, artist):
            status, text = show("netease " + name, url, headers, limit=420)
            if status == 200:
                print("first-char:", text[:1], "len", len(text))
        for name, url, headers in qq_variants(title, artist):
            if name == "musicu":
                body = json.dumps({
                    "req_1": {
                        "method": "DoSearchForQQMusicDesktop",
                        "module": "music.search.SearchCgiService",
                        "param": {"num_per_page": 5, "page_num": 1, "query": f"{title} {artist}",
                                  "search_type": 0},
                    }
                }, ensure_ascii=False)
                status, text = show("qq musicu", url,
                                    {"Referer": "https://y.qq.com/", "Content-Type": "application/json"},
                                    method="POST", body=body, limit=900)
            else:
                status, text = show("qq " + name, url, headers, limit=600)


if __name__ == "__main__":
    main()
