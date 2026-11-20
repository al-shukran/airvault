#!/usr/bin/env python3
"""Rebalances the shard lines of shards.txt from measured test times.

usage: gh run download <pr-fast run> --dir DIR; python3 -I scripts/ci/balance-shards.py scripts/ci/shards.txt DIR > new.txt

Every measured class of a shard gets a line, listed or not. A shard runs the test tasks of :app, :lumio and :shared side by side, so a lane ends with its slowest module, not with its total.
Each module's classes are therefore spread on their own, largest first onto the lane with the least time of that module. The
pure JVM modules, verify, fuzz and classes with no measured time keep their lines. app.lumio.btc.BtcScanTest (about 50 s alone)
keeps shard-1 to itself: no other :app, :lumio or :shared class goes there.
"""
import collections, glob, re, sys

LANES = ['shard-%d' % i for i in range(1, 9)]
MODULES = (':app', ':lumio', ':shared')
PINNED = {(':lumio', 'app.lumio.btc.BtcScanTest'): 'shard-1'}

shards_txt, junit = sys.argv[1], sys.argv[2]
times = collections.defaultdict(float)
for path in glob.glob(junit + '/junit-shard-*/**/*.xml', recursive=True):
    module = re.search(r'/(app|lumio|shared)/build/', path)
    suite = re.search(r'<testsuite name="([^"]+)"[^>]* time="([0-9.]+)"', open(path, errors='ignore').read(4000))
    if module and suite:
        times[(':' + module.group(1), suite.group(1))] += float(suite.group(2))

lines = open(shards_txt).read().splitlines()
rows, movable = [], set(times)
for line in lines:
    if not line.strip() or line.startswith('#'):
        continue
    lane, module, cls = line.split()
    if (module, cls) not in movable:
        rows.append((lane, module, cls))

load = collections.defaultdict(float)
for module, cls in sorted(movable, key=lambda unit: -times[unit]):
    lane = PINNED.get((module, cls))
    if lane is None:
        lane = min(LANES[1:], key=lambda l: (load[(module, l)], sum(load[(m, l)] for m in MODULES)))
    load[(module, lane)] += times[(module, cls)]
    rows.append((lane, module, cls))

for lane in LANES:
    print(lane, ' '.join('%s=%.0f' % (m, load[(m, lane)]) for m in MODULES), file=sys.stderr)
print('\n'.join(line for line in lines if line.startswith('#')))
print('\n'.join(' '.join(row) for row in sorted(rows)))
