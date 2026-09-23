#!/usr/bin/env bash
# 生成 GitHub Release 里的 update.json，App 靠它检测新版本。
# 用法：scripts/make-update-json.sh <标签> <APK 路径> <更新说明文件>
set -euo pipefail

tag="$1"
apk="$2"
notes_file="$3"
repo="${GITHUB_REPOSITORY:-duskedge/synopilot}"

version="${tag#v}"
code="$(./gradlew -q :app:printVersionCode -PversionTag="$tag" | tail -n 1)"
min_supported="$(grep -E '^minSupportedVersionCode=' .github/update-policy.properties | cut -d= -f2 | tr -d '[:space:]')"
size="$(wc -c < "$apk" | tr -d '[:space:]')"
sha256="$({ sha256sum "$apk" 2>/dev/null || shasum -a 256 "$apk"; } | cut -d' ' -f1)"
url="https://github.com/${repo}/releases/download/${tag}/$(basename "$apk")"

python3 - "$code" "$version" "${min_supported:-0}" "$url" "$size" "$sha256" "$notes_file" <<'PY'
import datetime, json, sys

code, name, min_supported, url, size, sha256, notes_file = sys.argv[1:]
with open(notes_file, encoding="utf-8") as f:
    notes = f.read().strip()
print(json.dumps({
    "versionCode": int(code),
    "versionName": name,
    "minSupportedVersionCode": int(min_supported),
    "publishedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "apk": {"url": url, "size": int(size), "sha256": sha256},
    "notes": notes,
}, ensure_ascii=False, indent=2))
PY
