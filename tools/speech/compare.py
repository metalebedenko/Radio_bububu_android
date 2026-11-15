#!/usr/bin/env python3
"""Compare two completed private HOST runs on the same immutable records. Never opens corpus inputs."""
import argparse
import collections
import json
import os
import pathlib
import re
import sys

QA = pathlib.Path(os.environ.get('BUBUBU_SPEECH_QA_DIR', pathlib.Path(__file__).resolve().parents[2] / '.private/speech-qa'))
ROOT = QA.resolve() / 'host/runs'


def valid(value):
    if not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}', value):
        raise argparse.ArgumentTypeError('Invalid run ID')
    return value


def spoken(sentence):
    return ' '.join(f['frontendSpoken'] for f in sentence['fragments'])


def compare(before, after):
    articles = []
    counts = collections.Counter()
    old_rows = {p.name for p in (ROOT / before).glob('*.json')} - {'summary.json', 'receipt.json'}
    new_rows = {p.name for p in (ROOT / after).glob('*.json')} - {'summary.json', 'receipt.json'}
    if not old_rows or old_rows != new_rows:
        raise ValueError('Runs must contain the same articles')
    for name in sorted(old_rows):
        old = json.loads((ROOT / before / name).read_text())
        new = json.loads((ROOT / after / name).read_text())
        if old['inputSha256'] != new['inputSha256'] or len(old['sentences']) != len(new['sentences']):
            raise ValueError('Article or sentence set changed: ' + name)
        deltas = []
        for a, b in zip(old['sentences'], new['sentences']):
            for k in ('index', 'raw', 'start', 'end'):
                if a[k] != b[k]:
                    raise ValueError('Canonical sentence changed: ' + name)
            if a['status'] != b['status']:
                kind = ('recovered' if b['status'] == 'ACCEPTED' else 'new_rejection'
                        if a['status'] == 'ACCEPTED' else 'changed_rejection')
            elif a['status'] != 'ACCEPTED':
                kind = 'still_rejected'
            elif spoken(a) != spoken(b):
                kind = 'changed_reading'
            elif [(f['start'], f['end']) for f in a['fragments']] != [(f['start'], f['end']) for f in b['fragments']]:
                kind = 'changed_fragmentation'
            else:
                kind = 'same_reading'
            counts[kind] += 1
            if kind != 'same_reading':
                deltas.append(dict(index=a['index'], start=a['start'], end=a['end'], kind=kind,
                                   oldStatus=a['status'], newStatus=b['status'],
                                   oldReason=a.get('reason'), newReason=b.get('reason')))
        articles.append(dict(id=old['id'], inputSha256=old['inputSha256'], deltas=deltas))
    return dict(before=before, after=after, counts=dict(counts), articles=articles)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=valid)
    parser.add_argument('after', type=valid)
    args = parser.parse_args()
    json.dump(compare(args.before, args.after), sys.stdout, indent=2, ensure_ascii=False)
    print()


if __name__ == '__main__':
    main()
