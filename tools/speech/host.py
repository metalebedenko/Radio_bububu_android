#!/usr/bin/env python3
"""Freeze and replay the production HOST speech planner on a private split of full articles.

    python3 tools/speech/host.py snapshot NAME
    python3 tools/speech/host.py run NAME train RUN_ID
    python3 tools/speech/host.py run NAME holdout RUN_ID --freeze-receipt PATH

Set BUBUBU_SPEECH_QA_DIR to use a different corpus and results directory.
Run snapshot after the desired experimentalDebug classes have been built. The same frozen snapshot
can be replayed with different run IDs. Keep corpus/results private. No Gradle, ADB or network.
"""
import argparse
import collections
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
QA = pathlib.Path(os.environ.get('BUBUBU_SPEECH_QA_DIR', ROOT / '.private' / 'speech-qa')).resolve()
STAGES = QA / 'host'
CLASSES = ROOT / 'build/app/intermediates/javac/experimentalDebug/compileExperimentalDebugJavaWithJavac/classes'
TOOLCHAIN = pathlib.Path(os.environ.get('BUBUBU_TOOLCHAIN_DIR', pathlib.Path.home() / 'Library/Developer/RadioBububuAndroid'))
JDK = pathlib.Path(os.environ.get('JAVA_HOME', TOOLCHAIN / 'zulu17.60.17-ca-jdk17.0.16-macosx_aarch64/zulu-17.jdk/Contents/Home'))
JAVA = ROOT / 'tools/speech/CorpusSpeechAudit.java'
SOURCE_AREAS = ['app/src/main/java/ru/bububu/radio/' + area for area in ['core', 'models', 'sources']]
SOURCE_AREAS += ['app/src/experimental/java/ru/bububu/silero']


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(2**20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def tree(path):
    return {p.relative_to(path).as_posix(): sha(p) for p in sorted(path.rglob('*')) if p.is_file()}


def write(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, indent=2, sort_keys=True, ensure_ascii=False)
        stream.write('\n')


def ident(value):
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_-]{0,100}', value):
        raise argparse.ArgumentTypeError('Expected an alphanumeric ID')
    return value


def snapshot(name):
    dest = STAGES / name
    if dest.exists():
        raise ValueError('Snapshot already exists')
    STAGES.mkdir(exist_ok=True, parents=True)
    with tempfile.TemporaryDirectory(dir=STAGES) as scratch:
        target = pathlib.Path(scratch) / name
        target.mkdir()
        shutil.copytree(CLASSES, target / 'classes')
        shutil.copytree(ROOT / 'app/src/main/assets/speech', target / 'assets/speech')
        for area in SOURCE_AREAS:
            shutil.copytree(ROOT / area, target / 'sources' / area)
        write(target / 'manifest.json', {k: tree(target / k) for k in ('classes', 'assets', 'sources')})
        target.rename(dest)
    print('Frozen', name, sha(dest / 'manifest.json'))


def summary(folder):
    total = collections.Counter()
    reasons = collections.Counter()
    rows = []
    for path in sorted(folder.glob('*.json')):
        article = json.loads(path.read_text())
        stats = collections.Counter()
        for sentence in article['sentences']:
            stats['sentences'] += 1
            stats[sentence['status']] += 1
            if 'reason' in sentence:
                reasons[sentence['reason']] += 1
            stats['fragments'] += len(sentence['fragments'])
            if sentence['status'] != 'ACCEPTED':
                # Half-open canonical section, count body UTF-16 units excluded by sentence rejection.
                stats['rejected_body_utf16'] += max(0, min(article['bodyEnd'], sentence['end'])
                                                       - max(article['bodyStart'], sentence['start']))
        total.update(stats)
        rows.append(dict(id=article['id'], input_sha256=article['inputSha256'], **stats))
    return dict(articles=rows, total=dict(total), reasons=dict(reasons))


def run(name, split, run_id, freeze, input_set):
    stage = STAGES / name
    manifest = stage / 'manifest.json'
    frozen = json.loads(manifest.read_text())
    for k in ('classes', 'assets', 'sources'):
        if tree(stage / k) != frozen[k]:
            raise ValueError('Snapshot changed: ' + k)
    seal = sha(manifest)
    if split == 'holdout':
        # A freeze receipt is created explicitly after candidate freeze; the runner cannot issue one.
        # The same receipt may name the frozen baseline for the paired comparison.
        release = json.loads(freeze.read_text()) if freeze else {}
        if not release.get('snapshot_manifest_sha256') or seal not in (
                release['snapshot_manifest_sha256'], release.get('baseline_snapshot_manifest_sha256')):
            raise ValueError('Holdout requires the explicit frozen-candidate receipt for this snapshot')
    inputs = sorted((QA / input_set / split).glob('*.json'))
    if not inputs:
        raise ValueError('No released ' + split + ' corpus records')
    dest = STAGES / 'runs' / run_id
    if dest.exists():
        raise ValueError('Run already exists')
    dest.parent.mkdir(parents=True, exist_ok=True)
    layoutlibs = sorted(TOOLCHAIN.glob('studio/*/Android Studio.app/Contents/plugins/design-tools/lib/layoutlib.jar'))
    layout = pathlib.Path(os.environ.get('BUBUBU_HOST_LAYOUTLIB', layoutlibs[-1] if layoutlibs else ''))
    if not layout.is_file():
        raise ValueError('Set BUBUBU_HOST_LAYOUTLIB to the local Android Studio layoutlib.jar')
    with tempfile.TemporaryDirectory(dir=dest.parent) as scratch:
        scratch = pathlib.Path(scratch)
        report = scratch / 'report'
        report.mkdir()
        harness = scratch / 'classes'
        harness.mkdir()
        cp = os.pathsep.join(map(str, (harness, stage / 'classes', stage / 'assets', layout)))
        subprocess.run([JDK / 'bin/javac', '-encoding', 'UTF-8', '-cp', cp, '-d', harness, JAVA], check=True)
        subprocess.run([JDK / 'bin/java', '-Xmx1g', '-cp', cp, 'ru.bububu.silero.stressprobe.CorpusSpeechAudit',
                        ROOT / '.private/assets/bundled-models/silero-accepted-v1.zip', report, split, *inputs], check=True)
        if len(list(report.glob('*.json'))) != len(inputs):
            raise ValueError('Incomplete article coverage')
        counts = summary(report)
        write(report / 'summary.json', counts)
        receipt = dict(split=split, input_set=input_set, snapshot=name, snapshot_manifest_sha256=seal,
                       harness_sources_sha256=sha(JAVA), runner_sha256=sha(pathlib.Path(__file__)),
                       layoutlib_sha256=sha(layout), jdk=(JDK / 'release').read_text().splitlines()[0],
                       max_raw_fragment_utf16={'legacy': 1000, 'source45': 512},
                       holdout_receipt=(sha(freeze) if split == 'holdout' else None),
                       reports_sha256=tree(report))
        write(report / 'receipt.json', receipt)
        report.rename(dest)
    print('Completed', run_id, 'articles', len(inputs), counts['total'], counts['reasons'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for action in ('snapshot', 'run'):
        cmd = commands.add_parser(action)
        cmd.add_argument('snapshot', type=ident)
        if action == 'run':
            cmd.add_argument('split', choices=['train', 'holdout'])
            cmd.add_argument('run_id', type=ident)
            cmd.add_argument('--freeze-receipt', type=pathlib.Path)
            cmd.add_argument('--input-set', type=ident, default='corpus')
    args = parser.parse_args()
    if args.command == 'snapshot':
        snapshot(args.snapshot)
    else:
        run(args.snapshot, args.split, args.run_id, args.freeze_receipt, args.input_set)


if __name__ == '__main__':
    main()
