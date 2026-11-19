"""Keep the local administration credential readable only by the Windows user."""
from contextlib import closing
import ctypes
from ctypes import wintypes
import os
import secrets
import sqlite3


def protect(data, decrypt=False):
    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    memory = ctypes.create_string_buffer(data)
    incoming = Blob(len(data), ctypes.cast(memory, ctypes.POINTER(ctypes.c_ubyte)))
    outgoing = Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    method = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    method.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                       ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    method.restype = wintypes.BOOL
    if not method(ctypes.byref(incoming), None, None, None, None, 1, ctypes.byref(outgoing)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(outgoing.data, outgoing.size)
    finally:
        free = ctypes.WinDLL("kernel32").LocalFree
        free.argtypes = [ctypes.c_void_p]
        free.restype = ctypes.c_void_p
        free(outgoing.data)


def local_admin_token(state):
    if os.name != "nt":
        return secrets.token_urlsafe(32)
    with closing(sqlite3.connect(state)) as db, db:
        db.execute("CREATE TABLE IF NOT EXISTS admin_access(id INTEGER PRIMARY KEY CHECK(id=1), encrypted BLOB NOT NULL)")
        db.execute("BEGIN IMMEDIATE")
        stored = db.execute("SELECT encrypted FROM admin_access WHERE id=1").fetchone()
        if stored:
            return protect(stored[0], decrypt=True).decode("ascii")
        token = secrets.token_urlsafe(32)
        db.execute("INSERT INTO admin_access VALUES(1,?)", (protect(token.encode("ascii")),))
        return token
