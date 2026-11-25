#!/usr/bin/env python3
"""最小的"大脑"：收到什么字，回一句固定格式的话。用来先把整条链路跑通，再换成真的 agent。
  python3 echo_brain.py 8799     然后 BRAIN_URL=http://127.0.0.1:8799/
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        reply = json.dumps({"text": "我听到你说：" + body["text"]}, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(reply)

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
