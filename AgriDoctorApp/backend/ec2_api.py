"""Private FastAPI entry point preserving the Android API contract."""

import asyncio
from http.client import HTTPConnection, HTTPException

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from server import AI_ROUTES, MAX_BODY_BYTES


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
ROUTES = {"/v1/lookup": MAX_BODY_BYTES, **AI_ROUTES}


def forward(method, path, body=None, headers=None):
    connection = HTTPConnection("127.0.0.1", 8766, timeout=115)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        upstream = connection.getresponse()
        return Response(
            upstream.read(),
            status_code=upstream.status,
            media_type="application/json",
            headers={"Cache-Control": "no-store"},
        )
    except (OSError, HTTPException):
        return JSONResponse({"error": "Máy chủ AgriDoctor đang bận hoặc chưa sẵn sàng."}, 503)
    finally:
        connection.close()


@app.get("/health")
def health():
    return forward("GET", "/health")


@app.post("/v1/{operation}")
async def call_backend(operation: str, request: Request):
    path = f"/v1/{operation}"
    if path not in ROUTES:
        return JSONResponse({"error": "Đường dẫn không tồn tại."}, 404)

    body = bytearray()
    try:
        async with asyncio.timeout(10):
            async for chunk in request.stream():
                if len(body) + len(chunk) > ROUTES[path]:
                    return JSONResponse({"error": "Dữ liệu gửi lên quá dài."}, 413)
                body.extend(chunk)
    except TimeoutError:
        return JSONResponse({"error": "Hết thời gian nhận dữ liệu."}, 408)

    headers = {
        name: request.headers[name]
        for name in ("Content-Type", "Authorization")
        if name in request.headers
    }
    return await run_in_threadpool(forward, "POST", path, bytes(body), headers)
