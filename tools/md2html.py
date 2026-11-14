"""Wandelt docs/FORSCHUNG.md in eine eigenständige, handy-lesbare HTML-Seite.

Aufruf: python3 tools/md2html.py docs/FORSCHUNG.md Forschung.html
"""
import sys

import markdown

CSS = """
:root { --bg:#111; --fg:#ededed; --muted:#9a9a9a; --line:#2c2c2c; --accent:#5be38a; }
@media (prefers-color-scheme: light) { :root { --bg:#fafafa; --fg:#1a1a1a; --muted:#666; --line:#ddd; --accent:#1a8a4a; } }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--fg);
       font: 17px/1.6 -apple-system, "Roboto", "Segoe UI", sans-serif; }
main { max-width: 720px; margin: 0 auto; padding: 24px 16px 64px; }
h1 { font-size: 1.6em; line-height: 1.25; margin: 0.2em 0 0.6em; }
h2 { font-size: 1.3em; margin-top: 2em; padding-top: 0.6em; border-top: 1px solid var(--line); }
h3 { font-size: 1.08em; margin-top: 1.6em; }
p, li { hyphens: auto; }
em { color: var(--muted); }
blockquote { margin: 1em 0; padding: 0.6em 1em; border-left: 3px solid var(--accent);
             background: rgba(127,127,127,0.08); border-radius: 0 8px 8px 0; }
blockquote p { margin: 0; }
table { width: 100%; border-collapse: collapse; font-size: 0.9em; display: block; overflow-x: auto; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { color: var(--muted); font-weight: 600; }
code { font-size: 0.9em; }
hr { border: 0; border-top: 1px solid var(--line); margin: 2em 0; }
"""

src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
body = markdown.markdown(text, extensions=["tables"])
title = next((l[2:].strip() for l in text.splitlines() if l.startswith("# ")), "Forschung")
html = f"""<!doctype html>
<html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title><style>{CSS}</style></head>
<body><main>{body}</main></body></html>
"""
open(dst, "w", encoding="utf-8").write(html)
print("ok", dst)
