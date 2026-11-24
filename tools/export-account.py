#!/usr/bin/env python3
"""Back up one Ratelog account through the API (fork, 2026-11-24: "make a copy of all my data so far and keep it git ignored").

Writes a dated folder under backups/ (git-ignored: the files hold your personal data) with one JSON file per kind of data,
a ratings.csv you can read in a spreadsheet, and manifest.json (when, from where, counts, what is not included).

    python tools/export-account.py --base https://ratelog-production.up.railway.app --token-file path/to/token.txt

The token (Profile > key icon, /settings/api) is read from --token-file or the RATELOG_TOKEN environment variable and is never
printed. Any token can read everything of its own account; nothing is written to the site.
"""
import argparse
import csv
import datetime as dt
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent


def main() -> int:
    parser = argparse.ArgumentParser(description="Back up a Ratelog account (read-only) into backups/.")
    parser.add_argument("--base", default=os.environ.get("RATELOG_URL", "https://ratelog-production.up.railway.app"))
    parser.add_argument("--token-file", help="file holding the API token (else RATELOG_TOKEN)")
    parser.add_argument("--out", help="output folder (default backups/<username>-<date>-<time>)")
    args = parser.parse_args()

    token = (pathlib.Path(args.token_file).read_text(encoding="utf-8") if args.token_file else os.environ.get("RATELOG_TOKEN", "")).strip()
    if not token:
        print("No token: pass --token-file or set RATELOG_TOKEN.", file=sys.stderr)
        return 2
    base = args.base.rstrip("/")

    def get(path: str, **params):
        query = ("?" + urllib.parse.urlencode(params)) if params else ""
        request = urllib.request.Request(base + path + query, headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)

    try:
        me = get("/api/v1/me")
    except urllib.error.HTTPError as e:
        print(f"The API refused the token ({e.code}). Create a new one at {base}/settings/api.", file=sys.stderr)
        return 1

    stamp = dt.datetime.now().strftime("%Y-%m-%d-%H%M")
    out = pathlib.Path(args.out) if args.out else REPO / "backups" / f"{me['username']}-{stamp}"
    out.mkdir(parents=True, exist_ok=True)

    def save(name: str, data) -> None:
        (out / name).write_text(json.dumps(data, indent=1, ensure_ascii=False), encoding="utf-8")

    data = {"me": me}
    data["ratings"] = get("/api/v1/ratings")
    data["statuses"] = get("/api/v1/statuses")
    lists = get("/api/v1/lists")
    data["lists"] = [get(f"/api/v1/lists/{entry['id']}") for entry in lists]
    data["saved_lists"] = get("/api/v1/lists/saved")
    try:
        tags = get("/api/v1/tags")
        data["tags"] = [{"tag": tag, "titles": get(f"/api/v1/tags/{tag['id']}/titles")["titles"]} for tag in tags]
    except urllib.error.HTTPError:
        data["tags"] = []  # tags are switched off on this server
    data["stats_all_time"] = get("/api/v1/stats", year="all")
    for name, value in data.items():
        save(f"{name}.json", value)

    # The ratings in a spreadsheet-friendly file: one row per movie, show and rated season.
    with open(out / "ratings.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["type", "title", "year", "season", "score", "whole_show_score", "rated_at", "tmdb_id", "review_title", "review"])

        def when(ms):
            return dt.datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d") if isinstance(ms, (int, float)) else (ms or "")

        for m in data["ratings"].get("movies", []):
            writer.writerow(["movie", m.get("title"), m.get("year"), "", m.get("score"), "", when(m.get("createdAt")), m.get("tmdbId"),
                             m.get("reviewTitle") or "", m.get("reviewText") or ""])
        for t in data["ratings"].get("tv", []):
            writer.writerow(["tv", t.get("title"), t.get("year"), "", t.get("score"), t.get("showScore") or "", "", t.get("tmdbId"), "", ""])
            for s in t.get("seasons") or []:
                writer.writerow(["tv season", t.get("title"), t.get("year"), s.get("season"), s.get("score"), "", when(s.get("createdAt")),
                                 t.get("tmdbId"), s.get("reviewTitle") or "", s.get("reviewText") or ""])

    statuses = data["statuses"]
    manifest = {
        "exported_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "server": base,
        "account": me.get("username"),
        "counts": {
            "movie_ratings": len(data["ratings"].get("movies", [])),
            "tv_ratings": len(data["ratings"].get("tv", [])),
            "statuses": statuses.get("counts"),
            "lists": len(data["lists"]),
            "list_items": sum(len(l.get("items", [])) for l in data["lists"]),
            "saved_lists": len(data["saved_lists"]) if isinstance(data["saved_lists"], list) else data["saved_lists"],
            "tags": len(data["tags"]),
        },
        "not_included": "people you follow, titles you follow for premieres, account settings and API tokens (the API has no read for them)",
        "restore": "ratings: PUT /api/v1/ratings/...; statuses: PUT /api/v1/statuses/...; lists: POST /api/v1/lists with the items' tmdbId + type (docs/api.md)",
    }
    save("manifest.json", manifest)
    print(f"Saved to {out}")
    print(json.dumps(manifest["counts"], indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
