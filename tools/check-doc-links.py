#!/usr/bin/env python3
"""Check every relative markdown link (and #anchor) in docs/, CLAUDE.md and README.md.

Usage: python tools/check-doc-links.py   (run from anywhere; exits 1 on any broken link)
Anchors follow GitHub's rules: lowercase, spaces -> '-', punctuation dropped.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LINK = re.compile(r'(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')


def slug(heading):
    h = re.sub(r'`', '', heading.strip().lower())
    h = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', h)
    h = re.sub(r'[^\w\- ]', '', h, flags=re.UNICODE)
    return h.replace(' ', '-')


_anchor_cache = {}


def anchors(path):
    if path not in _anchor_cache:
        seen, out, fence = {}, set(), False
        with open(path, encoding='utf-8') as f:
            for line in f:
                if line.lstrip().startswith('```'):
                    fence = not fence
                    continue
                m = re.match(r'^#{1,6}\s+(.*?)\s*#*\s*$', line) if not fence else None
                if m:
                    s = slug(m.group(1))
                    n = seen.get(s, 0)
                    seen[s] = n + 1
                    out.add(s if n == 0 else '%s-%d' % (s, n))
        _anchor_cache[path] = out
    return _anchor_cache[path]


def md_files():
    yield os.path.join(ROOT, 'CLAUDE.md')
    yield os.path.join(ROOT, 'README.md')
    for d, _, fs in os.walk(os.path.join(ROOT, 'docs')):
        for f in fs:
            if f.endswith('.md'):
                yield os.path.join(d, f)


def main():
    bad, total = [], 0
    for path in md_files():
        if not os.path.exists(path):
            continue
        fence = False
        with open(path, encoding='utf-8') as f:
            for n, line in enumerate(f, 1):
                if line.lstrip().startswith('```'):
                    fence = not fence
                    continue
                if fence:
                    continue
                text = re.sub(r'`[^`]*`', '', line)  # ignore links inside code spans
                for m in LINK.finditer(text):
                    target = m.group(1)
                    if re.match(r'^[a-z][a-z0-9+.-]*:', target) or target.startswith('//'):
                        continue
                    total += 1
                    file_part, _, frag = target.partition('#')
                    dest = path if file_part == '' else os.path.normpath(os.path.join(os.path.dirname(path), file_part))
                    rel = os.path.relpath(path, ROOT)
                    if not os.path.exists(dest):
                        bad.append('%s:%d: missing file %s' % (rel, n, target))
                    elif frag and dest.endswith('.md') and frag.lower() not in anchors(dest):
                        bad.append('%s:%d: missing anchor %s' % (rel, n, target))
    for b in bad:
        print(b)
    print('%d relative links checked, %d broken' % (total, len(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
