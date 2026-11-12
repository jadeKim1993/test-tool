"""하네스 전용 TCP 수신기 — 게스트 안에서 돈다(python3 는 Ubuntu 클라우드 이미지에 기본으로 있다).

127.0.0.1:9000 으로 받은 바이트에서 줄바꿈(NEWLINE 프레이밍) 수를 세어 /run/harness-sink.count 에 적는다.
테스트 도구가 보낸 건수와 실제로 도착한 건수를 맞춰 보는 용도다.
"""

import os
import socketserver
import sys
import threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9000
COUNT_FILE = "/run/harness-sink.count"

lock = threading.Lock()
count = 0


def publish():
    with open(COUNT_FILE + ".tmp", "w") as f:
        f.write(str(count))
    os.replace(COUNT_FILE + ".tmp", COUNT_FILE)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        global count
        while True:
            data = self.request.recv(65536)
            if not data:
                return
            with lock:
                count += data.count(b"\n")
                publish()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


publish()
Server(("127.0.0.1", PORT), Handler).serve_forever()
