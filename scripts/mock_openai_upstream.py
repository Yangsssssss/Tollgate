"""本地 mock OpenAI 上游：演示 Tollgate 数据面闭环用。

支持：
- 非流式 POST /v1/chat/completions → 标准 chat.completion JSON（含 usage）
- 流式（body.stream=true）→ SSE，最后带 usage 帧 + [DONE]
"""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # 静默访问日志
        pass

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        auth = self.headers.get("Authorization", "")
        if auth != "Bearer sk-mock-ok":
            self._json(401, {"error": {"message": "bad mock key"}})
            return

        prompt = sum(len(m.get("content", "")) for m in body.get("messages", []))
        reply = "mock 回复：" + body.get("messages", [{}])[-1].get("content", "")
        prompt_tokens = max(1, prompt // 2)
        completion_tokens = max(1, len(reply) // 2)

        if body.get("stream"):
            self._stream(reply, prompt_tokens, completion_tokens)
        else:
            self._json(200, {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "choices": [{"index": 0,
                             "message": {"role": "assistant", "content": reply},
                             "finish_reason": "stop"}],
                "usage": {"prompt_tokens": prompt_tokens,
                          "completion_tokens": completion_tokens,
                          "total_tokens": prompt_tokens + completion_tokens},
            })

    def _json(self, code, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _stream(self, reply, prompt_tokens, completion_tokens):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for i in range(0, len(reply), 6):
            chunk = reply[i:i + 6]
            self._sse(json.dumps({
                "id": "chatcmpl-mock", "object": "chat.completion.chunk",
                "choices": [{"index": 0, "delta": {"content": chunk}, "finish_reason": None}],
            }, ensure_ascii=False))
            time.sleep(0.05)
        self._sse(json.dumps({
            "id": "chatcmpl-mock", "object": "chat.completion.chunk",
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
        }))
        self._sse(json.dumps({
            "id": "chatcmpl-mock", "object": "chat.completion.chunk",
            "choices": [],
            "usage": {"prompt_tokens": prompt_tokens,
                      "completion_tokens": completion_tokens,
                      "total_tokens": prompt_tokens + completion_tokens},
        }))
        self.wfile.write(b"data: [DONE]\n\n")

    def _sse(self, payload):
        self.wfile.write(f"data: {payload}\n\n".encode())
        self.wfile.flush()


if __name__ == "__main__":
    print("mock upstream on :9999")
    ThreadingHTTPServer(("127.0.0.1", 9999), Handler).serve_forever()
