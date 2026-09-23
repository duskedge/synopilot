#!/usr/bin/env python3
"""
开发用的假 DSM 服务：没有群晖时也能在模拟器里走完 接入 → 登录 → 总览。

    python3 scripts/dev/mock-dsm.py            # 监听 0.0.0.0:5000
    python3 scripts/dev/mock-dsm.py --port 5050

模拟器里用 http://10.0.2.2:5000 访问宿主机。
账号：
  admin / admin   普通登录
  otp   / otp     需要两步验证码 123456
负载数据每次请求随机变化；硬盘 3 处于「警告」状态，方便看界面效果。
字段格式参照 DSM 7.2 的真实返回，只实现 App 用到的接口。

同一个端口上还模拟了：
  Container Manager   6 个容器（nginx-proxy-manager 异常退出）+ 2 个 Compose 项目
  Download Station    SYNO.DownloadStation.*
  qBittorrent WebUI   /api/v2/*（admin / adminadmin）
  Transmission RPC    /transmission/rpc（无密码，会先返回 409 要求会话 ID）
  File Station        内存里的虚拟文件系统：浏览、搜索、上传、下载、缩略图、分享链接
容器 qbittorrent / transmission 的端口映射指向这个端口，所以「自动发现」能直接用。
"""
import argparse
import base64
import json
import random
import re
import secrets
import struct
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

APIS = {
    "SYNO.API.Auth": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 7},
    "SYNO.DSM.Info": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 2},
    "SYNO.Core.System.Utilization": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Storage.CGI.Storage": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Core.ExternalDevice.UPS": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Docker.Container": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Docker.Container.Resource": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Docker.Container.Log": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Docker.Project": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.DownloadStation.Info": {"path": "DownloadStation/info.cgi", "minVersion": 1, "maxVersion": 2},
    "SYNO.DownloadStation.Task": {"path": "DownloadStation/task.cgi", "minVersion": 1, "maxVersion": 3},
    "SYNO.DownloadStation.Statistic": {"path": "DownloadStation/statistic.cgi", "minVersion": 1, "maxVersion": 1},
    **{f"SYNO.FileStation.{n}": {"path": "entry.cgi", "minVersion": 1, "maxVersion": v} for n, v in [
        ("List", 2), ("Search", 2), ("CreateFolder", 2), ("Rename", 2), ("Delete", 2), ("Download", 2), ("Thumb", 2),
        ("Upload", 3), ("Sharing", 3)]},
}
PORT = 5000
LOCK = threading.Lock()
GB = 1024 ** 3
MB = 1024 ** 2

# ---- 容器 -----------------------------------------------------------------
CONTAINERS = {
    "jellyfin": {"image": "jellyfin/jellyfin:10.10.3", "status": "running", "exit": 0, "ports": [8096], "limit": 4 * GB, "project": "media"},
    "qbittorrent": {"image": "linuxserver/qbittorrent:5.0.3", "status": "running", "exit": 0, "ports": ["QB", 6881], "limit": 2 * GB, "project": "media"},
    "transmission": {"image": "linuxserver/transmission:4.0.6", "status": "running", "exit": 0, "ports": ["TR"], "limit": 0, "project": "media"},
    "nginx-proxy-manager": {"image": "jc21/nginx-proxy-manager:2.11.3", "status": "exited", "exit": 137, "ports": [80, 443, 81], "limit": 512 * MB, "project": "infra"},
    "homeassistant": {"image": "ghcr.io/home-assistant/home-assistant:stable", "status": "running", "exit": 0, "ports": [8123], "limit": 0, "project": None},
    "old-backup": {"image": "alpine:3.20", "status": "exited", "exit": 0, "ports": [], "limit": 0, "project": None},
}
STARTED_AT = {k: time.time() - random.randint(2, 20) * 86400 for k in CONTAINERS}
PROJECTS = {"p-media": ("media", "/docker/media"), "p-infra": ("infra", "/docker/infra")}
LOG_LINES = [
    ("stdout", "[INF] Starting service"), ("stdout", "[INF] Listening on 0.0.0.0"), ("stdout", "[INF] Health check OK (200) 12ms"),
    ("stderr", "[WRN] Slow query 812ms"), ("stdout", "[INF] Scheduled task finished"), ("stdout", "[DBG] cache hit ratio 0.93"),
]


def container_ports(c):
    out = []
    for p in c["ports"]:
        if p == "QB":
            out.append({"host_port": PORT, "container_port": 8080, "type": "tcp"})
        elif p == "TR":
            out.append({"host_port": PORT, "container_port": 9091, "type": "tcp"})
        else:
            out.append({"host_port": p, "container_port": p, "type": "udp" if p == 6881 else "tcp"})
    return out


def up_status(name, c):
    if c["status"] == "running":
        days = int((time.time() - STARTED_AT[name]) / 86400)
        return f"Up {days} days" if days else "Up less than a minute"
    return f"Exited ({c['exit']}) 1 hour ago"


def docker(api, method, params):
    name = params.get("name", "").strip('"')
    if api == "SYNO.Docker.Container":
        if method == "list":
            return ok({"containers": [
                {"name": n, "image": c["image"], "status": c["status"], "exit_code": c["exit"], "up_status": up_status(n, c),
                 "memory_limit": c["limit"], "ports": container_ports(c)} for n, c in CONTAINERS.items()], "total": len(CONTAINERS)})
        if name not in CONTAINERS:
            return err(114)
        c = CONTAINERS[name]
        if method in ("start", "restart"):
            c.update(status="running", exit=0)
            STARTED_AT[name] = time.time()
            return ok({})
        if method == "stop":
            c.update(status="exited", exit=0)
            return ok({})
        if method == "get":
            return ok({
                "profile": {
                    "env_variables": [{"key": "PUID", "value": "1026"}, {"key": "PGID", "value": "100"}, {"key": "TZ", "value": "Asia/Shanghai"},
                                      {"key": "WEBUI_PASSWORD", "value": "hunter2"}],
                    "volume_bindings": [{"host_volume_file": f"/docker/{name}/config", "mount_point": "/config", "type": "rw"},
                                        {"host_volume_file": "/video", "mount_point": "/media", "type": "ro"}],
                    "port_bindings": container_ports(c),
                },
                "details": {"HostConfig": {"RestartPolicy": {"Name": "unless-stopped"}},
                            "NetworkSettings": {"Networks": {f"{c['project'] or 'bridge'}_default": {"IPAddress": f"172.20.0.{len(name) + 2}"}}}},
            })
    if api == "SYNO.Docker.Container.Resource":
        return ok({"resources": [
            {"name": n, "cpu": round(random.uniform(0.2, 18), 1), "memory": random.randint(60, 900) * MB,
             "memoryPercent": round(random.uniform(0.5, 6), 1)} for n, c in CONTAINERS.items() if c["status"] == "running"]})
    if api == "SYNO.Docker.Container.Log":
        if name not in CONTAINERS:
            return err(114)
        now = time.time()
        extra = [("stderr", "[ERR] worker process exited on signal 9"), ("stderr", "[ERR] OOMKilled: memory limit exceeded")] \
            if name == "nginx-proxy-manager" else []
        lines = LOG_LINES + extra
        # 每次多一条，方便测「跟随」
        tick = int(now / 2) % 1000
        lines = lines + [("stdout", f"[INF] tick {tick}")]
        logs = [{"created": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(now - (len(lines) - i) * 7)) + ".123456Z",
                 "stream": s, "text": t + "\n"} for i, (s, t) in enumerate(lines)]
        return ok({"logs": list(reversed(logs)), "total": len(logs)})
    if api == "SYNO.Docker.Project":
        if method == "list":
            return ok({pid: {"id": pid, "name": n, "path": path, "status": "RUNNING",
                             "containers": [{"Name": "/" + cn} for cn, c in CONTAINERS.items() if c["project"] == n]}
                       for pid, (n, path) in PROJECTS.items()})
        pid = params.get("id", "").strip('"')
        if pid not in PROJECTS:
            return err(114)
        members = [c for c in CONTAINERS.values() if c["project"] == PROJECTS[pid][0]]
        for c in members:
            if method.startswith("stop"):
                c.update(status="exited", exit=0)
            else:
                c.update(status="running", exit=0)
        return "STREAM"
    return err(103)


# ---- 下载任务（三个下载器共用的模拟逻辑）-----------------------------------
def make_task(name, size_gb, state, done=0.0, speed=0, cat=""):
    return {"name": name, "size": int(size_gb * GB), "done": int(size_gb * GB * done), "state": state,
            "speed": speed * MB, "up": 0, "cat": cat, "added": time.time()}


DS_TASKS = {
    "dbid_1": make_task("ubuntu-26.04-desktop-amd64.iso", 6.1, "downloading", 0.42, 9),
    "dbid_2": make_task("debian-13.1.0-amd64-DVD-1.iso", 3.9, "waiting"),
    "dbid_3": make_task("Big.Buck.Bunny.4K.mkv", 12.4, "finished", 1.0),
}
QB_TASKS = {
    "a1b2c3d4": make_task("Oppenheimer.2023.2160p.UHD.BluRay.x265", 58.2, "downloading", 0.63, 22, "movie"),
    "e5f6a7b8": make_task("Shogun.S01.2160p.DSNP.WEB-DL", 41.0, "pausedDL", 0.18, 0, "tv"),
    "c9d0e1f2": make_task("archlinux-2026.09.01-x86_64.iso", 1.2, "uploading", 1.0, 0),
    "f3a4b5c6": make_task("The.Bear.S03.1080p.WEB", 14.6, "queuedDL", 0.0, 0, "tv"),
}
TR_TASKS = {
    1: make_task("Fedora-Workstation-Live-43.iso", 2.4, 4, 0.8, 5),
    2: make_task("LibreOffice_25.8_Linux_x86-64_deb.tar.gz", 0.3, 0, 1.0),
}
LIMITS = {"qb": [0, 0], "tr": [0, 0], "ds": [0, 0]}
LAST_TICK = [time.time()]


def tick():
    now = time.time()
    dt = now - LAST_TICK[0]
    LAST_TICK[0] = now
    for tasks, downloading, seeding in ((DS_TASKS, "downloading", "finished"), (QB_TASKS, "downloading", "uploading"), (TR_TASKS, 4, 6)):
        for t in tasks.values():
            if t["state"] == downloading:
                t["speed"] = max(1, int(t["speed"] * random.uniform(0.85, 1.15))) if t["speed"] else 8 * MB
                t["done"] = min(t["size"], t["done"] + int(t["speed"] * dt))
                if t["done"] >= t["size"]:
                    t["state"], t["speed"] = seeding, 0
            else:
                t["speed"] = 0
            t["up"] = random.randint(100, 900) * 1024 if t["state"] in ("uploading", 6) else 0


# ---- File Station：内存里的虚拟文件系统 ---------------------------------------
def png(w, h, rgb):
    """生成一张带渐变的 PNG（不依赖 PIL）"""
    rows = b"".join(b"\x00" + bytes(v for x in range(w) for v in (
        min(255, rgb[0] + x * 60 // w), min(255, rgb[1] + y * 60 // h), rgb[2])) for y in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


NOW = int(time.time())
FS = {}


def fs_add(path, size=0, content=None, age=0, isdir=None):
    FS[path] = {"isdir": isdir if isdir is not None else (size == 0 and content is None and "." not in path.rsplit("/", 1)[-1]),
                "size": len(content) if content is not None else size, "mtime": NOW - age, "content": content}


for d in ["/video", "/video/Movies", "/video/TV", "/docker", "/docker/jellyfin", "/docker/media", "/photo", "/photo/2026",
          "/downloads", "/homes", "/homes/admin"]:
    fs_add(d, isdir=True, age=random.randint(3600, 90 * 86400))
fs_add("/video/Movies/Oppenheimer.2023.2160p.UHD.BluRay.x265.mkv", 58 * GB, age=5 * 86400)
fs_add("/video/Movies/Dune.Part.Two.2024.2160p.mkv", 62 * GB, age=40 * 86400)
fs_add("/video/Movies/poster.jpg", 412 * 1024, age=3600)
fs_add("/video/TV/Shogun.S01E01.2160p.mkv", 7 * GB, age=2 * 86400)
fs_add("/docker/media/compose.yaml", content=b"""services:
  jellyfin:
    image: jellyfin/jellyfin:10.10.3
    ports: ["8096:8096"]
    volumes:
      - /volume1/docker/jellyfin:/config
      - /volume1/video:/media:ro
    restart: unless-stopped
  qbittorrent:
    image: linuxserver/qbittorrent:5.0.3
    environment: [PUID=1026, PGID=100, TZ=Asia/Shanghai]
""", age=86400 * 3)
fs_add("/docker/jellyfin/log.txt", content=("\n".join(f"[2026-09-2{i % 10} 10:0{i % 6}:00] [INF] line {i}" for i in range(200))).encode(), age=600)
for i in range(1, 7):
    fs_add(f"/photo/2026/IMG_{2400 + i}.jpg", random.randint(2, 6) * MB, age=i * 86400)
fs_add("/downloads/ubuntu-26.04-desktop-amd64.iso", int(6.1 * GB), age=7200)
fs_add("/downloads/archive.zip", 18 * MB, age=86400 * 20)
fs_add("/homes/admin/笔记.md", content="# 家里 NAS\n\n- 每周日 01:30 关机\n- 硬盘 3 需要换\n".encode(), age=300)
SEARCHES = {}


def fs_entry(path, share=False):
    f = FS[path]
    return {"isdir": f["isdir"], "name": path.rsplit("/", 1)[-1], "path": path,
            "additional": {"size": f["size"], "time": {"mtime": f["mtime"]}, "real_path": "/volume1" + path if share else None,
                           "type": "" if f["isdir"] else path.rsplit(".", 1)[-1].upper()}}


def children(folder):
    prefix = folder.rstrip("/") + "/"
    return sorted(p for p in FS if p.startswith(prefix) and "/" not in p[len(prefix):])


def json_list(value):
    try:
        v = json.loads(value)
        return v if isinstance(v, list) else [v]
    except (ValueError, TypeError):
        return [x for x in (value or "").split(",") if x]


def file_station(api, method, params):
    name = api.rsplit(".", 1)[-1]
    with LOCK:
        if name == "List":
            if method == "list_share":
                shares = [p for p in FS if p.count("/") == 1]
                return ok({"shares": [fs_entry(p, share=True) for p in sorted(shares)], "total": len(shares), "offset": 0})
            folder = params.get("folder_path", "").strip('"')
            if folder not in FS or not FS[folder]["isdir"]:
                return err(408)
            items = children(folder)
            return ok({"files": [fs_entry(p) for p in items], "total": len(items), "offset": 0})
        if name == "Search":
            if method == "start":
                pattern = params.get("pattern", "").strip("*").lower()
                roots = json_list(params.get("folder_path"))
                found = [p for p in FS if any(p.startswith(r.rstrip("/") + "/") for r in roots) and pattern in p.rsplit("/", 1)[-1].lower()]
                tid = secrets.token_hex(4)
                SEARCHES[tid] = found
                return ok({"taskid": tid})
            if method == "list":
                found = SEARCHES.get(params.get("taskid"), [])
                return ok({"files": [fs_entry(p) for p in found if p in FS], "total": len(found), "finished": True, "offset": 0})
            SEARCHES.pop(params.get("taskid"), None)
            return ok({})
        if name == "CreateFolder":
            path = params.get("folder_path", "").rstrip("/") + "/" + params.get("name", "")
            if path in FS:
                return err(414)
            fs_add(path, isdir=True)
            return ok({"folders": [fs_entry(path)]})
        if name == "Rename":
            path, new = params.get("path", ""), params.get("name", "")
            if path not in FS:
                return err(408)
            target = path.rsplit("/", 1)[0] + "/" + new
            if target in FS:
                return err(414)
            for p in [p for p in FS if p == path or p.startswith(path + "/")]:
                FS[target + p[len(path):]] = FS.pop(p)
            return ok({"files": [fs_entry(target)]})
        if name == "Delete":
            for path in json_list(params.get("path")):
                for p in [p for p in FS if p == path or p.startswith(path + "/")]:
                    FS.pop(p)
            return ok({})
        if name == "Sharing":
            path = params.get("path", "")
            if path not in FS:
                return err(408)
            sid = secrets.token_urlsafe(6)
            qr = "data:image/png;base64," + base64.b64encode(png(120, 120, (20, 20, 20))).decode()
            return ok({"links": [{"id": sid, "url": f"https://gofile.me/7aB3c/{sid}", "qrcode": qr, "path": path,
                                  "date_expired": params.get("date_expired", ""), "has_password": bool(params.get("password"))}]})
        if name in ("Download", "Thumb"):
            path = params.get("path", "").strip('"')
            f = FS.get(path)
            if not f or f["isdir"]:
                return err(408)
            ext = path.rsplit(".", 1)[-1].lower()
            if name == "Thumb" or ext in ("jpg", "jpeg", "png"):
                size = {"small": 96, "medium": 240, "large": 480}.get(params.get("size"), 480)
                seed = sum(path.encode()) % 180
                return ("BYTES", png(size, size * 3 // 4, (40 + seed % 120, 80, 160 + seed % 90)), "image/png")
            if f["content"] is not None:
                return ("BYTES", f["content"], "application/octet-stream")
            return ("BYTES", b"\0" * min(f["size"], 24 * MB), "application/octet-stream")
    return err(103)


def parse_multipart(body, ctype):
    boundary = ctype.split("boundary=", 1)[-1].strip('"').encode()
    fields, file_name, file_data = {}, None, b""
    for part in body.split(b"--" + boundary):
        if b"\r\n\r\n" not in part:
            continue
        head, data = part.split(b"\r\n\r\n", 1)
        data = data[:-2] if data.endswith(b"\r\n") else data
        head = head.decode("utf-8", "replace")
        m = re.search(r'name="([^"]+)"', head)
        fn = re.search(r'filename="([^"]*)"', head)
        if fn:
            file_name, file_data = fn.group(1), data
        elif m:
            fields[m.group(1)] = data.decode("utf-8", "replace")
    return fields, file_name, file_data


def upload(params, body, ctype):
    fields, name, data = parse_multipart(body, ctype)
    folder = fields.get("path", "/")
    if not name:
        return err(101)
    path = folder.rstrip("/") + "/" + name
    with LOCK:
        if path in FS and fields.get("overwrite") not in ("true", "overwrite"):
            return err(414)
        # 模拟 create_parents
        parts = folder.strip("/").split("/")
        for i in range(1, len(parts) + 1):
            d = "/" + "/".join(parts[:i])
            if d not in FS:
                fs_add(d, isdir=True)
        fs_add(path, content=data if len(data) < 2 * MB else None, size=len(data), isdir=False)
    return ok({})


def download_station(api, method, params):
    with LOCK:
        tick()
        if api == "SYNO.DownloadStation.Info":
            if method == "getinfo":
                return ok({"is_manager": True, "version": 4213, "version_string": "4.0.1-4213"})
            if method == "getconfig":
                return ok({"bt_max_download": LIMITS["ds"][0], "bt_max_upload": LIMITS["ds"][1], "default_destination": "downloads"})
            if method == "setserverconfig":
                LIMITS["ds"] = [int(params.get("bt_max_download", 0)), int(params.get("bt_max_upload", 0))]
                return ok({})
        if api == "SYNO.DownloadStation.Statistic":
            return ok({"speed_download": sum(t["speed"] for t in DS_TASKS.values()), "speed_upload": 0})
        if api == "SYNO.DownloadStation.Task":
            ids = [i for i in params.get("id", "").split(",") if i]
            if method == "list":
                return ok({"offset": 0, "total": len(DS_TASKS), "tasks": [
                    {"id": i, "title": t["name"], "size": t["size"], "status": t["state"], "type": "bt", "username": "admin",
                     "additional": {"detail": {"destination": "downloads/iso", "connected_seeders": 18, "connected_leechers": 4},
                                    "transfer": {"size_downloaded": t["done"], "size_uploaded": 0, "speed_download": t["speed"], "speed_upload": 0}}}
                    for i, t in DS_TASKS.items()]})
            if method == "create":
                for uri in params.get("uri", "").split(","):
                    m = re.search(r"dn=([^&]+)", uri)
                    DS_TASKS[f"dbid_{len(DS_TASKS) + 10}"] = make_task(m.group(1) if m else uri.rsplit("/", 1)[-1], random.uniform(1, 8), "waiting")
                return ok({})
            for i in ids:
                if i not in DS_TASKS:
                    continue
                if method == "pause":
                    DS_TASKS[i]["state"] = "paused"
                elif method == "resume":
                    DS_TASKS[i]["state"] = "downloading" if DS_TASKS[i]["done"] < DS_TASKS[i]["size"] else "finished"
                elif method == "delete":
                    DS_TASKS.pop(i)
            return ok([{"id": i, "error": 0} for i in ids])
    return err(103)


def qbittorrent(path, params, body, headers):
    """返回 (状态码, 内容, 额外响应头)"""
    with LOCK:
        tick()
        if path == "auth/login":
            if params.get("username") == "admin" and params.get("password") == "adminadmin":
                return 200, "Ok.", {"Set-Cookie": "SID=mockqbsid; HttpOnly; path=/"}
            return 200, "Fails.", {}
        if "SID=mockqbsid" not in (headers.get("Cookie") or ""):
            return 403, "Forbidden", {}
        if path == "app/version":
            return 200, "v5.0.3", {}
        if path == "app/webapiVersion":
            return 200, "2.11.2", {}
        if path == "torrents/info":
            return 200, [{
                "hash": h, "name": t["name"], "size": t["size"], "progress": t["done"] / t["size"], "dlspeed": t["speed"], "upspeed": t["up"],
                "eta": int((t["size"] - t["done"]) / t["speed"]) if t["speed"] else 8640000, "state": t["state"],
                "ratio": round(random.uniform(0.2, 1.8), 2) if t["state"] == "uploading" else 0.0, "save_path": "/downloads/" + (t["cat"] or ""),
                "num_seeds": 31, "num_leechs": 6, "category": t["cat"], "added_on": int(t["added"]),
            } for h, t in QB_TASKS.items()], {}
        if path == "transfer/info":
            return 200, {"dl_info_speed": sum(t["speed"] for t in QB_TASKS.values()), "up_info_speed": sum(t["up"] for t in QB_TASKS.values()),
                         "dl_rate_limit": LIMITS["qb"][0], "up_rate_limit": LIMITS["qb"][1]}, {}
        if path == "transfer/setDownloadLimit":
            LIMITS["qb"][0] = int(params.get("limit", 0))
            return 200, "", {}
        if path == "transfer/setUploadLimit":
            LIMITS["qb"][1] = int(params.get("limit", 0))
            return 200, "", {}
        if path == "torrents/categories":
            return 200, {"movie": {"name": "movie", "savePath": "/downloads/movie"}, "tv": {"name": "tv", "savePath": "/downloads/tv"}}, {}
        if path == "torrents/add":
            text = body.decode("utf-8", "replace")
            m = re.search(r'name="urls"\r\n\r\n(.*?)\r\n--', text, re.S)
            cat = re.search(r'name="category"\r\n\r\n(.*?)\r\n--', text, re.S)
            for url in (m.group(1).splitlines() if m else []):
                dn = re.search(r"dn=([^&]+)", url)
                QB_TASKS[secrets.token_hex(4)] = make_task(dn.group(1) if dn else url[-30:], random.uniform(1, 20), "downloading", 0, 6,
                                                           cat.group(1) if cat else "")
            return 200, "Ok.", {}
        hashes = params.get("hashes", "").split("|")
        for h in hashes:
            t = QB_TASKS.get(h)
            if not t:
                continue
            if path in ("torrents/stop", "torrents/pause"):
                t["state"] = "stoppedUP" if t["done"] >= t["size"] else "stoppedDL"
            elif path in ("torrents/start", "torrents/resume"):
                t["state"] = "uploading" if t["done"] >= t["size"] else "downloading"
            elif path == "torrents/delete":
                QB_TASKS.pop(h)
        return 200, "", {}


TR_SESSION = "mock-tr-session"


def transmission(req):
    with LOCK:
        tick()
        method, args = req.get("method"), req.get("arguments", {})
        if method == "session-get":
            return {"version": "4.0.6 (38c164933e)", "rpc-version": 17, "download-dir": "/downloads",
                    "speed-limit-down-enabled": LIMITS["tr"][0] > 0, "speed-limit-down": LIMITS["tr"][0],
                    "speed-limit-up-enabled": LIMITS["tr"][1] > 0, "speed-limit-up": LIMITS["tr"][1]}
        if method == "session-set":
            LIMITS["tr"] = [args.get("speed-limit-down", 0) if args.get("speed-limit-down-enabled") else 0,
                            args.get("speed-limit-up", 0) if args.get("speed-limit-up-enabled") else 0]
            return {}
        if method == "session-stats":
            return {"downloadSpeed": sum(t["speed"] for t in TR_TASKS.values()), "uploadSpeed": sum(t["up"] for t in TR_TASKS.values())}
        if method == "torrent-get":
            return {"torrents": [{
                "id": i, "name": t["name"], "totalSize": t["size"], "percentDone": t["done"] / t["size"], "rateDownload": t["speed"],
                "rateUpload": t["up"], "eta": int((t["size"] - t["done"]) / t["speed"]) if t["speed"] else -1, "status": t["state"],
                "uploadRatio": 0.4 if t["state"] == 6 else -1, "downloadDir": "/downloads/complete", "peersSendingToUs": 12,
                "peersGettingFromUs": 3, "error": 0, "errorString": "", "labels": [],
            } for i, t in TR_TASKS.items()]}
        if method == "torrent-add":
            url = args.get("filename", "")
            dn = re.search(r"dn=([^&]+)", url)
            new_id = max(TR_TASKS, default=0) + 1
            TR_TASKS[new_id] = make_task(dn.group(1) if dn else url[-30:], random.uniform(1, 8), 4, 0, 4)
            return {"torrent-added": {"id": new_id, "name": TR_TASKS[new_id]["name"]}}
        for i in args.get("ids", []):
            t = TR_TASKS.get(i)
            if not t:
                continue
            if method == "torrent-stop":
                t["state"] = 0
            elif method == "torrent-start":
                t["state"] = 6 if t["done"] >= t["size"] else 4
            elif method == "torrent-remove":
                TR_TASKS.pop(i)
        return {}
SESSIONS = {}
STARTED = time.time() - 38 * 86400 - 14 * 3600
TB = 1000 ** 4


def ok(data=None):
    return {"success": True, "data": data if data is not None else {}}


def err(code):
    return {"success": False, "error": {"code": code}}


def handle(api, method, params):
    if api == "SYNO.API.Auth":
        if method == "logout":
            SESSIONS.pop(params.get("_sid"), None)
            return ok()
        account, password = params.get("account"), params.get("passwd")
        if (account, password) not in {("admin", "admin"), ("otp", "otp")}:
            return err(400)
        if account == "otp" and params.get("otp_code") != "123456" and params.get("device_id") != "mock-device-token":
            return err(404 if params.get("otp_code") else 403)
        sid = secrets.token_hex(12)
        SESSIONS[sid] = account
        data = {"sid": sid, "synotoken": secrets.token_hex(8), "is_portal_port": False}
        if params.get("enable_device_token") == "yes":
            data["did"] = "mock-device-token"
        return ok(data)

    if params.get("_sid") not in SESSIONS:
        return err(119)

    if api == "SYNO.DSM.Info":
        return ok({
            "model": "DS923+", "ram": 16384, "serial": "MOCK923SERIAL",
            "temperature": random.randint(41, 47), "temperature_warn": False,
            "uptime": int(time.time() - STARTED), "version": "72806",
            "version_string": "DSM 7.2.2-72806 Update 2",
        })
    if api == "SYNO.Core.System.Utilization":
        user = random.randint(5, 40)
        return ok({
            "cpu": {"user_load": user, "system_load": random.randint(2, 8), "other_load": 1,
                    "1min_load": random.randint(40, 160), "5min_load": 62, "15min_load": 51},
            "memory": {"real_usage": random.randint(34, 44), "total_real": 16258252,
                       "avail_real": 10039588, "cached": 3355443, "memory_size": 16777216},
            "network": [
                {"device": "total", "rx": random.randint(8, 50) * 1024 * 1024, "tx": random.randint(1, 10) * 1024 * 1024},
                {"device": "eth0", "rx": 0, "tx": 0},
            ],
        })
    if api == "SYNO.Storage.CGI.Storage":
        disk = lambda i, model, vendor, temp, status, size, ssd=False, prefix="sata": {
            "id": f"{prefix}{i}", "name": f"Drive {i}", "longName": f"硬盘 {i}" if not ssd else "M.2 缓存 1",
            "model": model, "vendor": vendor, "serial": f"SN{i:04d}", "temp": temp,
            "status": "normal", "overview_status": status, "smart_status": "normal",
            "size_total": str(size), "isSsd": ssd, "diskType": "M.2 NVMe" if ssd else "SATA",
        }
        return ok({
            "disks": [
                disk(1, "ST8000NT001", "Seagate", 35, "normal", 8 * TB),
                disk(2, "ST8000NT001", "Seagate", 36, "normal", 8 * TB),
                disk(3, "WD80EFPX", "WDC", 41, "warning", 8 * TB),
                disk(4, "WD80EFPX", "WDC", 37, "normal", 8 * TB),
                disk(0, "Samsung SSD 990 EVO 1TB", "Samsung", 46, "normal", TB, ssd=True, prefix="nvme"),
            ],
            "storagePools": [{"id": "reuse_1", "num_id": 1, "status": "normal", "device_type": "shr_with_1_disk_protect",
                              "size": {"total": str(int(23.4 * TB)), "used": str(int(14.3 * TB))}}],
            "volumes": [{"id": "volume_1", "num_id": 1, "status": "normal", "fs_type": "btrfs", "vol_path": "/volume1",
                         "size": {"total": str(int(23.4 * TB)), "used": str(int(14.3 * TB))}}],
        })
    if api == "SYNO.Core.ExternalDevice.UPS":
        return ok({"enable": True, "status": "usb_ups_status_online", "charge": 100, "runtime": 2520, "model": "Back-UPS BK650M2"})
    if api.startswith("SYNO.Docker."):
        with LOCK:
            return docker(api, method, params)
    if api.startswith("SYNO.DownloadStation."):
        return download_station(api, method, params)
    if api.startswith("SYNO.FileStation."):
        return file_station(api, method, params)
    return err(103)


class Handler(BaseHTTPRequestHandler):
    def _read(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0) or 0)) if self.command == "POST" else b""
        params = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
        ctype = self.headers.get("Content-Type", "")
        if body and "application/x-www-form-urlencoded" in ctype:
            params.update({k: v[0] for k, v in parse_qs(body.decode()).items()})
        return params, body

    def _send(self, payload, status=200, headers=None, text=False):
        body = (payload if text else json.dumps(payload, ensure_ascii=False)).encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8" if text else "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, data, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        # 大文件分块发送，模拟真实的下载速度（约 12 MB/s）
        for i in range(0, len(data), 256 * 1024):
            self.wfile.write(data[i:i + 256 * 1024])
            if len(data) > 4 * MB:
                time.sleep(0.02)

    def _route(self):
        path = urlparse(self.path).path
        params, body = self._read()
        self._api = params.get("api", "")
        if path == "/webapi/query.cgi":
            q = params.get("query", "all")
            data = APIS if q == "all" else {k: v for k, v in APIS.items() if k in q.split(",")}
            return self._send(ok(data))
        if path.startswith("/webapi/"):
            if params.get("api") == "SYNO.FileStation.Upload":
                if params.get("_sid") not in SESSIONS:
                    return self._send(err(119))
                return self._send(upload(params, body, self.headers.get("Content-Type", "")))
            result = handle(params.get("api"), params.get("method"), params)
            if isinstance(result, tuple) and result[0] == "BYTES":
                return self._send_bytes(result[1], result[2])
            if result == "STREAM":
                return self._send("Pulling...\nCreating...\nStarted\n", text=True)
            return self._send(result)
        if path.startswith("/api/v2/"):
            status, content, headers = qbittorrent(path[len("/api/v2/"):], params, body, self.headers)
            return self._send(content, status, headers, text=isinstance(content, str))
        if path == "/transmission/rpc":
            if self.headers.get("X-Transmission-Session-Id") != TR_SESSION:
                return self._send("", 409, {"X-Transmission-Session-Id": TR_SESSION}, text=True)
            try:
                result = transmission(json.loads(body or b"{}"))
            except Exception as e:  # noqa: BLE001
                return self._send({"result": str(e)})
            return self._send({"result": "success", "arguments": result})
        self.send_response(404)
        self.end_headers()

    do_GET = _route
    do_POST = _route

    def log_message(self, fmt, *args):
        print("[mock-dsm]", self.command, urlparse(self.path).path, getattr(self, "_api", ""), args[1] if len(args) > 1 else "")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5000)
    args = ap.parse_args()
    PORT = args.port
    print(f"mock DSM on http://0.0.0.0:{args.port}  (模拟器里用 http://10.0.2.2:{args.port})")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
