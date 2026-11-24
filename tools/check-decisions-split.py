#!/usr/bin/env python3
"""Verify that splitting design-decisions.md lost nothing.

Usage: python tools/check-decisions-split.py <old design-decisions.md> [docs/decisions dir]
Compares the multiset of non-empty lines (heading levels ignored; relative link
prefixes '](x.md' vs '](../x.md' normalised) of the old file with the topic files.
The old title and one-line preamble live in the index and are excluded.
"""
import collections
import glob
import os
import re
import sys


def norm(line):
    line = line.rstrip()
    line = re.sub(r'^#{1,6} ', '# ', line)
    return re.sub(r'\]\(\.\./([A-Za-z0-9_-]+\.md)', r'](\1', line)


def lines(path, skip=0):
    with open(path, encoding='utf-8') as f:
        return [norm(l) for i, l in enumerate(f.read().split('\n')) if i >= skip and l.strip()]


old_path = sys.argv[1]
d = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'docs', 'decisions')
old = collections.Counter(lines(old_path, skip=3))  # skip '# Design decisions', blank, preamble
new = collections.Counter()
for p in sorted(glob.glob(os.path.join(d, '*.md'))):
    new.update(lines(p))
lost = old - new
# The old group headings 'Features' and 'UI' became '## Standing decisions' in each topic file.
for h in ('# Features', '# UI'):
    lost.pop(h, None)
added = new - old
print('old non-empty lines (minus title+preamble): %d' % sum(old.values()))
print('new non-empty lines in %d topic files:        %d' % (len(glob.glob(os.path.join(d, '*.md'))), sum(new.values())))
print('lost lines: %d' % sum(lost.values()))
for k in lost: print('  LOST', k[:100])
print('added lines: %d (one title per file + "Standing decisions" group headings)' % sum(added.values()))
for k in added: print('  ADDED', k[:100])
sys.exit(1 if lost else 0)
