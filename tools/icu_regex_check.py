"""Validate every Kotlin regex literal of the app with ICU 74 — the regex engine Android uses (java.util.regex on the JVM accepts things ICU rejects, e.g. a bare "}"). Exit 1 on any error."""
import ctypes, re, sys, pathlib

icu = ctypes.CDLL("libicui18n.so.74")
uregex_open = icu.uregex_open_74
uregex_open.restype = ctypes.c_void_p
uregex_open.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
uregex_close = icu.uregex_close_74
uregex_close.argtypes = [ctypes.c_void_p]

class UParseError(ctypes.Structure):
    _fields_ = [("line", ctypes.c_int32), ("offset", ctypes.c_int32), ("pre", ctypes.c_uint16 * 16), ("post", ctypes.c_uint16 * 16)]

def icu_ok(pattern, flags=0):
    data = pattern.encode("utf-16-le")
    buf = ctypes.create_string_buffer(data)
    status = ctypes.c_int(0)
    pe = UParseError()
    h = uregex_open(buf, len(data) // 2, flags, ctypes.byref(pe), ctypes.byref(status))
    if h: uregex_close(h)
    return status.value <= 0, status.value, pe.offset

def unescape_kotlin(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            m = {"\\": "\\", '"': '"', "'": "'", "$": "$", "n": "\n", "t": "\t", "r": "\r", "b": "\b"}
            if n in m: out.append(m[n]); i += 2; continue
            if n == "u": out.append(chr(int(s[i+2:i+6], 16))); i += 6; continue
        out.append(c); i += 1
    return "".join(out)

STR = r'(?:"""(?:.*?)"""|"(?:[^"\\]|\\.)*")'
lit = re.compile(r'(?:Regex\(\s*|Pattern\.compile\(\s*)(' + STR + r'(?:\s*\+\s*' + STR + r')*)|(' + STR + r')\s*\.toRegex\(', re.S)
piece = re.compile(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', re.S)
bad = total = dyn = 0
for path in sys.argv[1:]:
    text = pathlib.Path(path).read_text()
    for m in lit.finditer(text):
        chunk = m.group(1) or m.group(2)
        parts = []
        for pm in piece.finditer(chunk):
            parts.append(pm.group(1) if pm.group(1) is not None else unescape_kotlin(pm.group(2)))
        pat = "".join(parts); src = chunk; is_raw = True
        line = text.count("\n", 0, m.start()) + 1
        total += 1
        if "${" in pat or re.search(r'\$[a-zA-Z_]', pat if is_raw else src.replace("\\$", "")):
            dyn += 1
            print(f"DYNAMIC {path}:{line}: {pat[:120]}")
            continue
        ok, st, off = icu_ok(pat)
        if not ok:
            bad += 1
            print(f"ICU-ERROR {path}:{line}: status={st} offset={off}: {pat}")
print(f"checked={total} bad={bad} dynamic={dyn}")
sys.exit(1 if bad else 0)
