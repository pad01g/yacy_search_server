"""Embedding service for YaCy's vector search: an OpenAI-compatible POST /v1/embeddings for one ONNX model on CPU.

The default model is intfloat/multilingual-e5-small (384 dimensions, about 100 languages, trained for retrieval). e5
expects the prefixes "query: " and "passage: "; YaCy adds them (search.vector.queryPrefix / passagePrefix).
Only the standard library is used for HTTP; fastembed runs the model with onnxruntime.
"""
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from fastembed import TextEmbedding
from fastembed.common.model_description import ModelSource, PoolingType

MODEL = os.environ.get("EMBEDDINGS_MODEL", "intfloat/multilingual-e5-small")
PORT = int(os.environ.get("EMBEDDINGS_PORT", "8095"))
MAX_INPUTS = 64           # texts per request
MAX_CHARS = 4000          # per text; e5 reads at most 512 tokens anyway
MAX_BODY = 4 << 20
# texts per model run: long passages (512 tokens) take a lot of memory in the attention layers
BATCH = int(os.environ.get("EMBEDDINGS_BATCH", "4"))

if MODEL == "intfloat/multilingual-e5-small":
    # not in fastembed's own list: the quantized ONNX export of the same model
    TextEmbedding.add_custom_model(model=MODEL, pooling=PoolingType.MEAN, normalization=True,
                                   sources=ModelSource(hf="Xenova/multilingual-e5-small"), dim=384,
                                   model_file="onnx/model_quantized.onnx")

model = TextEmbedding(MODEL, cache_dir=os.environ.get("EMBEDDINGS_CACHE", "/models"))
# Batches (YaCy embedding its index) run one at a time; single texts (search queries) do not queue behind them.
# onnxruntime sessions and the tokenizer may be used from several threads.
batch_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server_version = "yacy-embeddings"
    # keep-alive: clients such as Java's HttpClient reuse connections; every answer has a content-length
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quiet
        pass

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/health", "/v1/models"):
            return self.reply(200, {"object": "list", "data": [{"id": MODEL, "object": "model"}]})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/v1/embeddings":
            return self.reply(404, {"error": "not found"})
        n = int(self.headers.get("content-length") or 0)
        if n <= 0 or n > MAX_BODY:
            return self.reply(413, {"error": "body too large or empty"})
        try:
            req = json.loads(self.rfile.read(n))
            inputs = req.get("input")
            if isinstance(inputs, str):
                inputs = [inputs]
            if not isinstance(inputs, list) or not inputs or len(inputs) > MAX_INPUTS or not all(isinstance(x, str) for x in inputs):
                return self.reply(400, {"error": f"input must be a string or 1..{MAX_INPUTS} strings"})
            if req.get("model") not in (None, MODEL):
                return self.reply(400, {"error": f"this service runs {MODEL}"})
        except (ValueError, AttributeError):
            return self.reply(400, {"error": "bad JSON"})
        texts = [x[:MAX_CHARS] for x in inputs]
        if len(texts) == 1:
            vectors = list(model.embed(texts, batch_size=1))
        else:
            with batch_lock:
                vectors = list(model.embed(texts, batch_size=BATCH))
        self.reply(200, {
            "object": "list", "model": MODEL,
            "data": [{"object": "embedding", "index": i, "embedding": [round(float(v), 6) for v in vec]} for i, vec in enumerate(vectors)],
            "usage": {"prompt_tokens": 0, "total_tokens": 0},
        })


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--download":
        print("model ready:", MODEL)
        sys.exit(0)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
