#!/usr/bin/env python3
"""Read-only C15 comparison. capture --serial SERIAL FILE; compare BEFORE AFTER; self-test."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

PACKAGE = "ru.bububu.radio.test"
KEYS = ("book", "player", "source_prefs", "model_prefs", "wav", "part")

# On Android the only command with access to application data is an unprivileged
# run-as shell. Its output grammar excludes file names, XML, audio and note text.
PROBE = r'''
set -eu
export LC_ALL=C
fail() { exit 7; }
# Refuse unsupported paths and distinguish missing children from inaccessible parents.
dir() {
    [ ! -L "$1" ] || fail
    if [ -e "$1" ]; then
        [ -d "$1" ] && [ -r "$1" ] && [ -x "$1" ] && ls -a "$1" >/dev/null || fail
    fi
}
digest() {
    sum=$(sha256sum < "$1") || fail
    printf '%s' "${sum%% *}"
}
hash_text() {
    sum=$(printf '%s' "$1" | sha256sum) || fail
    printf '%s' "${sum%% *}"
}
atomic() {
    label=$1; path=$2; base=-; bak=-; pending=-
    for suffix in '' .bak .new; do
        f="$path$suffix"
        [ ! -L "$f" ] || fail
        if [ -e "$f" ]; then
            [ -f "$f" ] && [ -r "$f" ] || fail
            val=$(digest "$f") || fail
            case "$suffix" in '') base=$val;; .bak) bak=$val;; .new) pending=$val;; esac
        fi
    done
    auth=$bak; [ "$auth" != - ] || auth=$base
    if [ "$auth" = - ]; then
        [ "$pending" = - ] || fail
        printf '%s 0 - -\n' "$label"
    else
        committed=$path; [ "$bak" = - ] || committed="$path.bak"
        [ -s "$committed" ] || fail
        # Separate authority and physical layout; a .bak/.new change is visible even
        # when the committed contents survive. No AtomicFile recovery is triggered.
        layout=$(hash_text "$base $bak $pending") || fail
        printf '%s 1 %s %s\n' "$label" "$auth" "$layout"
    fi
}
audio() {
    label=$1; suffix=$2; rows=''; count=0
    for f in no_backup/notes/*"$suffix" no_backup/notes/.*"$suffix"; do
        [ ! -L "$f" ] || fail
        [ -e "$f" ] || continue
        [ -f "$f" ] && [ -r "$f" ] || fail
        name=$(hash_text "${f##*/}") || fail
        contents=$(digest "$f") || fail
        rows="$rows$name$contents"; count=$((count+1))
    done
    if [ "$count" = 0 ]; then printf '%s 0 - -\n' "$label"; else
        set_hash=$(hash_text "$rows") || fail
        printf '%s %s %s -\n' "$label" "$count" "$set_hash"
    fi
}
quiet() {
    if [ "${1:-}" != fixture ]; then
        uid=$(id -u) || fail
        [ "$uid" -ge 10000 ] || fail
        case "$(pwd -P)" in
            /data/data/ru.bububu.radio.test|/data/user/*/ru.bububu.radio.test|/mnt/expand/*/user/*/ru.bububu.radio.test) ;;
            *) fail ;;
        esac
        command -v pidof >/dev/null || fail
        if pidof ru.bububu.radio.test >/dev/null; then fail; else [ "$?" = 1 ] || fail; fi
    fi
}
quiet "${1:-}"
dir .; dir no_backup; dir no_backup/notes; dir files; dir shared_prefs
atomic book no_backup/notes/notes-v1.bin
atomic player files/player-state-v1.json
atomic source_prefs shared_prefs/demo_ui_v1.xml
atomic model_prefs shared_prefs/local-model-choice.xml
audio wav .wav
audio part .wav.part
quiet "${1:-}"
'''

class Unverified(Exception):
    pass


def command(args, **kwargs):
    result = subprocess.run(args, capture_output=True, **kwargs)
    if result.returncode:
        raise Unverified("Чтение не подтверждено: проверьте соединение, run-as, остановку приложения и состояние файлов.")
    return result.stdout


def parse(output):
    lines = output.decode("ascii").splitlines()
    if len(lines) != len(KEYS):
        raise Unverified("Неполный ответ проверки.")
    records = {}
    for key, line in zip(KEYS, lines):
        match = re.fullmatch(r"([a-z_]+) (0|[1-9][0-9]*) (-|[0-9a-f]{64}) (-|[0-9a-f]{64})", line)
        if not match or match[1] != key:
            raise Unverified("Ответ проверки не соответствует ожидаемой схеме.")
        n, digest, layout = int(match[2]), match[3], match[4]
        atomic = key in KEYS[:4]
        if n == 0 and (digest != "-" or layout != "-") or n and (
                digest == "-" or atomic and (n != 1 or layout == "-") or not atomic and layout != "-"):
            raise Unverified("Некорректный счётчик или отпечаток.")
        records[key] = [n, digest, layout]
    return records


def collect(args, cwd=None):
    first = parse(command(args, input=PROBE.encode(), cwd=cwd))
    second = parse(command(args, input=PROBE.encode(), cwd=cwd))
    if first != second:
        raise Unverified("При повторном чтении состояние изменилось.")
    return first


def capture(args):
    device = [args.adb, "-s", args.serial]
    serial = command(device + ["get-serialno"]).strip()
    if serial != args.serial.encode():
        raise Unverified("ADB не подтвердил выбранное устройство.")
    data = collect(device + ["shell", "-T", "run-as", PACKAGE, "sh", "-s"])
    record = dict(schema=1, package=PACKAGE, device=hashlib.sha256(serial).hexdigest(), groups=data)
    with os.fdopen(os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as stream:
        json.dump(record, stream)
    for key, (n, _, _) in data.items():
        print(f"{key}: {n}")


def load(path):
    obj = json.loads(Path(path).read_text())
    if obj["schema"] != 1 or obj["package"] != PACKAGE or not re.fullmatch("[0-9a-f]{64}", obj["device"]):
        raise Unverified("Неизвестная схема, устройство или пакет.")
    data = obj["groups"]
    if set(data) != set(KEYS):
        raise Unverified("Снимок неполон.")
    wire = "\n".join(" ".join([k] + [str(x) for x in data[k]]) for k in KEYS)
    return obj["device"], parse(wire.encode())


def compare(a, b):
    failed = False
    for key in KEYS:
        n, digest, layout = a[key]
        m, newer, layout_newer = b[key]
        if (n, digest) != (m, newer):
            status = "CHANGED"
            failed = True
        elif layout != layout_newer:
            status = "CONTENT_EQUAL_LAYOUT_CHANGED"
            failed = True
        else:
            status = "BYTES_EQUAL" if n else "ABSENT_BOTH_CONTENT_NOT_VERIFIED"
        print(f"{key}: {status} ({n} → {m})")
    return 1 if failed else 0


def self_test():
    from contextlib import redirect_stdout
    from io import StringIO
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        def probe():
            return collect(["sh", "-s", "--", "fixture"], cwd=tmp)
        assert all(v[0] == 0 for v in probe().values())
        notes = root / "no_backup/notes"
        notes.mkdir(parents=True)
        (root / "files").mkdir()
        (root / "shared_prefs").mkdir()
        base = notes / "notes-v1.bin"
        bak = notes / "notes-v1.bin.bak"
        pending = notes / "notes-v1.bin.new"
        pending.write_bytes(b"unfinished")
        try:
            probe()
        except Unverified:
            pass
        else:
            raise AssertionError("Lone .new was accepted")
        base.write_bytes(b"uncommitted")
        bak.write_bytes(b"committed")
        audio = notes / "synthetic-id.wav"
        audio.write_bytes(b"synthetic-wave")
        part = notes / "synthetic-id.wav.part"
        part.write_bytes(b"part")
        (root / "files/player-state-v1.json").write_bytes(b"cursor")
        (root / "shared_prefs/demo_ui_v1.xml").write_bytes(b"source-choice")
        (root / "shared_prefs/local-model-choice.xml").write_bytes(b"model-choice")
        before = probe()
        assert before["book"][1] == hashlib.sha256(b"committed").hexdigest()
        assert all(v[0] == 1 for v in before.values())
        with redirect_stdout(StringIO()):
            assert compare(before, probe()) == 0
            part.write_bytes(b"PART")
            changed = probe()
            assert before["part"] != changed["part"] and before["wav"] == changed["wav"]
            part.write_bytes(b"part")
            bak.replace(base)
            after_recovery = probe()
            assert before["book"][1] == after_recovery["book"][1]
            assert before["book"][2] != after_recovery["book"][2]
            assert compare(before, after_recovery) == 1
            audio.rename(notes / "another-id.wav")
            assert before["wav"] != probe()["wav"]
        assert before["part"] == probe()["part"]
        audio.symlink_to(base)
        try:
            probe()
        except Unverified:
            pass
        else:
            raise AssertionError("Symlink was accepted")
    print("PASS: отсутствие, два стабильных чтения, авторитет .bak, отказ при одиночном .new/ссылке, WAV/части, выбор, изменение имени.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="action", required=True)
    c = subs.add_parser("capture")
    c.add_argument("--adb", default="adb")
    c.add_argument("--serial", required=True)
    c.add_argument("output")
    c = subs.add_parser("compare")
    c.add_argument("before"); c.add_argument("after")
    subs.add_parser("self-test")
    args = parser.parse_args()
    if args.action == "self-test":
        self_test(); return 0
    if args.action == "capture":
        capture(args); return 0
    dev_a, a = load(args.before)
    dev_b, b = load(args.after)
    if dev_a != dev_b:
        raise Unverified("Снимки сняты с разных устройств.")
    return compare(a, b)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, Unverified) as err:
        print("NOT_VERIFIED:", err if isinstance(err, Unverified) else "Ошибка доступа или формата.", file=sys.stderr)
        sys.exit(2)
