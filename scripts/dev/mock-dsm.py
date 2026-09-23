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
"""
import argparse
import json
import random
import secrets
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

APIS = {
    "SYNO.API.Auth": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 7},
    "SYNO.DSM.Info": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 2},
    "SYNO.Core.System.Utilization": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Storage.CGI.Storage": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
    "SYNO.Core.ExternalDevice.UPS": {"path": "entry.cgi", "minVersion": 1, "maxVersion": 1},
}
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
    return err(103)


class Handler(BaseHTTPRequestHandler):
    def _params(self):
        params = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
        if self.command == "POST":
            body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
            params.update({k: v[0] for k, v in parse_qs(body).items()})
        return params

    def _send(self, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _route(self):
        path = urlparse(self.path).path
        params = self._params()
        if path == "/webapi/query.cgi":
            q = params.get("query", "all")
            data = APIS if q == "all" else {k: v for k, v in APIS.items() if k in q.split(",")}
            return self._send(ok(data))
        if path == "/webapi/entry.cgi":
            return self._send(handle(params.get("api"), params.get("method"), params))
        self.send_response(404)
        self.end_headers()

    do_GET = _route
    do_POST = _route

    def log_message(self, fmt, *args):
        print("[mock-dsm]", self.command, urlparse(self.path).path, self._last_api() if self.command else "")

    def _last_api(self):
        return ""


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5000)
    args = ap.parse_args()
    print(f"mock DSM on http://0.0.0.0:{args.port}  (模拟器里用 http://10.0.2.2:{args.port})")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
