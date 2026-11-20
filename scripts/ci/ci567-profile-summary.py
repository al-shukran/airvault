#!/usr/bin/env python3
# ci-567 measurement only (not for merge): prints the Summary and the slowest tasks of every Gradle --profile report that the
# current lane wrote under build/reports/profile, so the job log carries the numbers.
import glob
import re

DURATION = re.compile(r'(?:(\d+)h\s*)?(?:(\d+)m\s*)?([\d.]+)s')
SUMMARY_ROW = re.compile(r'<tr>\s*<td>(.*?)</td>\s*<td class="numeric">(.*?)</td>\s*</tr>', re.S)
TASK_ROW = re.compile(r'<tr>\s*<td[^>]*>(.*?)</td>\s*<td class="numeric">(.*?)</td>\s*<td>(.*?)</td>\s*</tr>', re.S)


def seconds(text):
    match = DURATION.search(text)
    if not match:
        return 0.0
    hours, minutes, secs = match.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(secs)


def section(text, start, end):
    begin = text.find(start)
    if begin < 0:
        return ''
    stop = text.find(end, begin) if end else -1
    return text[begin:stop] if stop > begin else text[begin:]


for path in sorted(glob.glob('build/reports/profile/profile-*.html')):
    with open(path, encoding='utf-8') as handle:
        text = handle.read()
    print(f'CI567 profile {path.rsplit("/", 1)[-1]}')
    for name, value in SUMMARY_ROW.findall(section(text, 'id="tab0"', 'id="tab1"')):
        print(f'CI567 summary {name.strip()} = {value.strip()}')
    tasks = TASK_ROW.findall(section(text, 'id="tab4"', ''))
    tasks = [(name.strip(), value.strip(), result.strip()) for name, value, result in tasks]
    ranked = sorted(tasks, key=lambda row: seconds(row[1]), reverse=True)
    for name, value, result in ranked[:40]:
        if name in ('', ':'):
            continue
        print(f'CI567 task {value:>10} {result or "EXECUTED":<12} {name}')
    outcomes = {}
    for _, _, result in tasks:
        key = result or 'EXECUTED'
        outcomes[key] = outcomes.get(key, 0) + 1
    print(f'CI567 task outcomes {outcomes}')
