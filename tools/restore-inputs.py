#!/usr/bin/env python3
"""Restore pinned experimental runtime/model inputs using Python 3.9+ standard library.

  python3 tools/restore-inputs.py
  python3 tools/restore-inputs.py --runtime-only --runtime-cache /path/to/cache
  python3 tools/restore-inputs.py --models-bundle /path/to/RadioBububu-models-archive-2026-11-15.zip
  python3 tools/restore-inputs.py --self-test

No model, JAR, native library or downloaded code is executed. Existing files are
reused only after size and SHA-256 checks; mismatches fail without replacement.
Runtime URLs and pins are in third_party/runtime-inputs.json. Model release URL,
outer ZIP pin and the exact member list are in third_party/model-inputs.json.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CHUNK = 1024 * 1024
MAX_FILE_BYTES = 1024 * 1024 * 1024
MAX_DOWNLOAD_SECONDS = 1800
DOWNLOAD_HOSTS = frozenset((
    'repo.maven.apache.org', 'github.com', 'release-assets.githubusercontent.com',
    'objects.githubusercontent.com', 'github-releases.githubusercontent.com',
))


class RestoreError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise RestoreError(message)


def relative_path(value):
    require(isinstance(value, str) and value and '\\' not in value and '\x00' not in value,
            'Invalid manifest path')
    path = PurePosixPath(value)
    require(not path.is_absolute() and '..' not in path.parts and '.' not in path.parts
            and str(path) == value and ':' not in value, 'Unsafe manifest path')
    return path


def pin(value, size_key='bytes'):
    require(isinstance(value, dict), 'Invalid pinned file record')
    size, digest = value.get(size_key), value.get('sha256')
    require(type(size) is int and 0 <= size <= MAX_FILE_BYTES, 'Invalid pinned size')
    require(isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest), 'Invalid SHA-256 pin')
    return size, digest


def checked_path(root, relative):
    """Do not follow symlinks in any existing destination component."""
    path = root
    require(not path.is_symlink() and (not path.exists() or path.is_dir()), 'Unsafe destination root')
    for part in relative_path(relative).parts:
        path = path / part
        require(not path.is_symlink(), 'Symlink destination refused')
    return path


def verify(path, expected):
    require(not path.is_symlink() and path.is_file(), 'Input is missing or not a regular file: ' + path.name)
    require(path.stat().st_size == expected[0], 'Size mismatch: ' + path.name)
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(CHUNK), b''):
            digest.update(chunk)
    require(digest.hexdigest() == expected[1], 'SHA-256 mismatch: ' + path.name)


def present(path, expected):
    if path.exists() or path.is_symlink():
        verify(path, expected)
        return True
    return False


def install_stream(source, destination, expected, deadline=None):
    """Stage bytes, verify them, then publish without overwriting a prior file."""
    if present(destination, expected):
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.restore-', dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            digest = hashlib.sha256()
            count = 0
            while True:
                if deadline is not None:
                    require(time.monotonic() <= deadline, 'Download time limit exceeded')
                chunk = source.read(min(CHUNK, expected[0] - count + 1))
                if not chunk:
                    break
                count += len(chunk)
                require(count <= expected[0], 'Input exceeds pinned size: ' + destination.name)
                digest.update(chunk)
                output.write(chunk)
            require(count == expected[0], 'Input is shorter than pinned size: ' + destination.name)
            require(digest.hexdigest() == expected[1], 'SHA-256 mismatch: ' + destination.name)
            output.flush()
            os.fsync(output.fileno())
        try:
            # Same-directory hard link is atomic and fails if a file appeared meanwhile.
            os.link(temporary, destination)
        except FileExistsError:
            verify(destination, expected)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def valid_url(url):
    require(isinstance(url, str), 'Missing HTTPS URL')
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'https' and parsed.hostname in DOWNLOAD_HOSTS
            and parsed.username is None and parsed.password is None
            and parsed.port in (None, 443) and not parsed.fragment,
            'Download URL is outside the approved HTTPS hosts')
    return url


class PinnedRedirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 5

    def redirect_request(self, request, response, code, message, headers, new_url):
        valid_url(new_url)
        return super().redirect_request(request, response, code, message, headers, new_url)


def download(url, expected, cache, name):
    valid_url(url)
    relative_path(name)
    require('/' not in name, 'Cache name must be a filename')
    cache.mkdir(parents=True, exist_ok=True)
    target = checked_path(cache, name)
    if present(target, expected):
        print('Verified cache:', name)
        return target
    opener = urllib.request.build_opener(PinnedRedirects())
    request = urllib.request.Request(url, headers={'User-Agent': 'RadioBububu-archive-restore/1',
                                                'Accept-Encoding': 'identity'})
    print('Downloading:', name)
    with opener.open(request, timeout=30) as response:
        valid_url(response.geturl())
        require(response.status == 200, 'Unexpected download status')
        require(response.headers.get('Content-Encoding', 'identity') == 'identity', 'Encoded download refused')
        advertised = response.headers.get('Content-Length')
        if advertised is not None:
            require(advertised.isdecimal() and int(advertised) == expected[0], 'Unexpected Content-Length')
        install_stream(response, target, expected, time.monotonic() + MAX_DOWNLOAD_SECONDS)
    return target


def copy_checked(source, target, expected):
    verify(source, expected)
    if not present(target, expected):
        with source.open('rb') as stream:
            install_stream(stream, target, expected)


def zip_members(archive, exact_names=None):
    """Validate the directory before extracting any named member."""
    infos = archive.infolist()
    require(len(infos) <= 4096, 'Too many ZIP members')
    names, files = set(), {}
    for info in infos:
        raw = info.filename[:-1] if info.is_dir() else info.filename
        relative_path(raw)
        require(info.filename not in names, 'Duplicate ZIP member')
        names.add(info.filename)
        mode = info.external_attr >> 16
        kind = stat.S_IFMT(mode)
        require(kind in (0, stat.S_IFDIR if info.is_dir() else stat.S_IFREG), 'Nonregular ZIP member refused')
        require(not info.flag_bits & 1, 'Encrypted ZIP member refused')
        if not info.is_dir():
            files[info.filename] = info
    if exact_names is not None:
        require(set(files) == set(exact_names), 'Model ZIP member list differs from manifest')
    return files


def extract_checked(archive, info, target, expected):
    require(info.file_size == expected[0], 'ZIP member size mismatch: ' + info.filename)
    if not present(target, expected):
        with archive.open(info) as source:
            install_stream(source, target, expected)


def load_manifest(path):
    require(not path.is_symlink() and path.is_file() and path.stat().st_size <= 1024 * 1024,
            'Missing or oversized input manifest: ' + path.name)
    manifest = json.loads(path.read_text(encoding='utf-8'))
    require(isinstance(manifest, dict) and manifest.get('schema') == 1, 'Unknown manifest schema')
    return manifest


def restore_runtime(root, cache):
    rows = load_manifest(root / 'third_party/runtime-inputs.json').get('inputs')
    require(isinstance(rows, list) and len(rows) == 6, 'Expected six runtime inputs')
    destinations = set()
    for row in rows:
        require(isinstance(row, dict), 'Invalid runtime record')
        relative = row.get('destination')
        require(isinstance(relative, str) and relative.startswith('.private/vendor/'), 'Invalid runtime destination')
        require(relative not in destinations, 'Duplicate runtime destination')
        destinations.add(relative)
        target = checked_path(root, relative)
        expected = pin(row)
        if present(target, expected):
            print('Verified installed:', relative)
            continue
        if 'archive' not in row:
            source = download(row.get('url'), expected, cache, target.name)
            copy_checked(source, target, expected)
        else:
            outer = row['archive']
            source = download(outer.get('url'), pin(outer), cache, 'sherpa-onnx-1.13.8.aar')
            member = row.get('member')
            relative_path(member)
            with zipfile.ZipFile(source) as archive:
                files = zip_members(archive)
                require(member in files, 'Missing pinned runtime member')
                extract_checked(archive, files[member], target, expected)
        print('Restored:', relative)
    restore_runtime_licenses(root)


def restore_runtime_licenses(root):
    directory = checked_path(root, 'third_party/licenses')
    require(directory.is_dir(), 'Missing third_party/licenses')
    sources = sorted(directory.rglob('*'))
    require(any(p.is_file() for p in sources), 'No runtime license files')
    for source in sources:
        require(not source.is_symlink(), 'Symlink license refused')
        if source.is_dir():
            continue
        require(source.is_file() and source.stat().st_size <= 2 * 1024 * 1024, 'Invalid runtime license file')
        body = source.read_bytes()
        expected = (len(body), hashlib.sha256(body).hexdigest())
        relative = '.private/vendor/sherpa/licenses/' + source.relative_to(directory).as_posix()
        copy_checked(source, checked_path(root, relative), expected)


def restore_models(root, local_bundle):
    manifest = load_manifest(root / 'third_party/model-inputs.json')
    outer = manifest.get('archive', {})
    expected_archive = pin(outer, 'size')
    rows = manifest.get('files')
    require(isinstance(rows, list) and 4 <= len(rows) <= 128, 'Invalid model member list')
    entries = {}
    for row in rows:
        require(isinstance(row, dict), 'Invalid model record')
        name = row.get('path')
        relative_path(name)
        require(name in ('bundled-models/silero-accepted-v1.zip', 'bundled-models/gigaam-accepted-v1.zip')
                or name.startswith('model-licenses/'), 'Unexpected model destination')
        require(name not in entries, 'Duplicate model manifest member')
        entries[name] = pin(row, 'size')
    required = {'bundled-models/silero-accepted-v1.zip', 'bundled-models/gigaam-accepted-v1.zip',
                'model-licenses/NOTICE.txt', 'model-licenses/LICENSE.GigaAM-punct'}
    require(required <= set(entries), 'Required models/notices absent from manifest')
    targets = {name: checked_path(root, '.private/assets/' + name) for name in entries}
    installed = [present(targets[name], expected) for name, expected in entries.items()]
    if all(installed) and local_bundle is None:
        print('All model files verified; no download required.')
        return
    if local_bundle is not None:
        bundle = local_bundle
        verify(bundle, expected_archive)
    else:
        cache = checked_path(root, '.private/downloads/models')
        bundle = download(outer.get('url'), expected_archive, cache, outer.get('name'))
    with zipfile.ZipFile(bundle) as archive:
        files = zip_members(archive, entries)
        for name, expected in entries.items():
            extract_checked(archive, files[name], targets[name], expected)
            print('Verified model member:', name)


def self_test():
    """Synthetic, offline safety/idempotency checks; never accesses project inputs."""
    import contextlib
    import unittest

    class SafetyTests(unittest.TestCase):
        def setUp(self):
            self.temp = tempfile.TemporaryDirectory(prefix='bububu-restore-test-')
            self.root = Path(self.temp.name)
            self.data = b'bounded synthetic input'
            self.expected = (len(self.data), hashlib.sha256(self.data).hexdigest())

        def tearDown(self):
            self.temp.cleanup()

        def test_install_and_idempotency(self):
            target = checked_path(self.root, 'vendor/input')
            install_stream(io.BytesIO(self.data), target, self.expected)
            install_stream(io.BytesIO(b'not consumed'), target, self.expected)
            verify(target, self.expected)

        def test_wrong_existing_is_preserved(self):
            target = self.root / 'input'
            target.write_bytes(b'old')
            with self.assertRaises(RestoreError):
                install_stream(io.BytesIO(self.data), target, self.expected)
            self.assertEqual(target.read_bytes(), b'old')

        def test_bad_stream_does_not_publish(self):
            for data in (b'x', self.data + b'x', b'x' * len(self.data)):
                with self.assertRaises(RestoreError):
                    install_stream(io.BytesIO(data), self.root / 'input', self.expected)
                self.assertFalse((self.root / 'input').exists())
                self.assertFalse(list(self.root.glob('.restore-*')))

        def test_paths_and_symlinks(self):
            for name in ('../x', '/x', 'a/../x', 'a//x', 'a\\x', 'C:/x'):
                with self.assertRaises(RestoreError):
                    checked_path(self.root, name)
            (self.root / 'link').symlink_to(self.root, target_is_directory=True)
            with self.assertRaises(RestoreError):
                checked_path(self.root, 'link/input')

        def test_zip_exact_names_and_symlink(self):
            for extra in ('../escape', 'extra', 'symlink'):
                buffer = io.BytesIO()
                with zipfile.ZipFile(buffer, 'w') as archive:
                    archive.writestr('valid', self.data)
                    entry = zipfile.ZipInfo(extra)
                    if extra == 'symlink':
                        entry.create_system = 3
                        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(entry, b'x')
                buffer.seek(0)
                with zipfile.ZipFile(buffer) as archive, self.assertRaises(RestoreError):
                    zip_members(archive, {'valid'})

        def test_checked_zip_extraction(self):
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, 'w') as archive:
                archive.writestr('license/', b'')
                archive.writestr('license/NOTICE', self.data)
            buffer.seek(0)
            with zipfile.ZipFile(buffer) as archive:
                files = zip_members(archive, {'license/NOTICE'})
                extract_checked(archive, files['license/NOTICE'], self.root / 'NOTICE', self.expected)
            verify(self.root / 'NOTICE', self.expected)

        def test_urls_are_https_and_allowlisted(self):
            for url in ('http://github.com/x', 'https://localhost/x', 'file:///tmp/x',
                        'https://user:pass@github.com/x', 'https://github.com:80/x'):
                with self.assertRaises(RestoreError):
                    valid_url(url)
            valid_url('https://repo.maven.apache.org/maven2/test.jar')

        def test_runtime_restore_from_verified_cache_and_retry(self):
            cache = self.root / 'cache'
            cache.mkdir()
            third_party = self.root / 'third_party'
            (third_party / 'licenses').mkdir(parents=True)
            (third_party / 'licenses/LICENSE.test').write_bytes(self.data)
            rows = []
            for index in range(3):
                name = 'input%d.jar' % index
                (cache / name).write_bytes(self.data)
                rows.append({'destination': '.private/vendor/pytorch/' + name,
                             'bytes': self.expected[0], 'sha256': self.expected[1],
                             'url': 'https://repo.maven.apache.org/maven2/' + name})
            outer = cache / 'sherpa-onnx-1.13.8.aar'
            with zipfile.ZipFile(outer, 'w') as archive:
                for index in range(3):
                    archive.writestr('member%d' % index, self.data)
                archive.writestr('unused-file', b'not extracted')
            body = outer.read_bytes()
            archive_pin = {'url': 'https://github.com/k2-fsa/example.aar', 'bytes': len(body),
                           'sha256': hashlib.sha256(body).hexdigest()}
            for index in range(3):
                rows.append({'destination': '.private/vendor/sherpa/input%d' % index,
                             'bytes': self.expected[0], 'sha256': self.expected[1],
                             'archive': archive_pin, 'member': 'member%d' % index})
            (third_party / 'runtime-inputs.json').write_text(json.dumps({'schema': 1, 'inputs': rows}))
            with contextlib.redirect_stdout(io.StringIO()):
                restore_runtime(self.root, cache)
                restore_runtime(self.root, cache)
            for row in rows:
                verify(self.root / row['destination'], self.expected)
            self.assertFalse((self.root / '.private/vendor/sherpa/unused-file').exists())
            verify(self.root / '.private/vendor/sherpa/licenses/LICENSE.test', self.expected)

        def test_models_local_bundle_and_retry(self):
            names = ('bundled-models/silero-accepted-v1.zip', 'bundled-models/gigaam-accepted-v1.zip',
                     'model-licenses/NOTICE.txt', 'model-licenses/LICENSE.GigaAM-punct')
            bundle = self.root / 'models.zip'
            with zipfile.ZipFile(bundle, 'w') as archive:
                for name in names:
                    archive.writestr(name, self.data)
            body = bundle.read_bytes()
            manifest = {'schema': 1, 'archive': {'name': 'models.zip', 'size': len(body),
                        'sha256': hashlib.sha256(body).hexdigest(), 'url': 'https://github.com/example/models.zip'},
                        'files': [{'path': name, 'size': self.expected[0], 'sha256': self.expected[1]} for name in names]}
            (self.root / 'third_party').mkdir()
            (self.root / 'third_party/model-inputs.json').write_text(json.dumps(manifest))
            with contextlib.redirect_stdout(io.StringIO()):
                restore_models(self.root, bundle)
                restore_models(self.root, None)
            for name in names:
                verify(self.root / '.private/assets' / name, self.expected)
            bundle.write_bytes(b'bad archive')
            with self.assertRaises(RestoreError):
                restore_models(self.root, bundle)

    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SafetyTests))
    return 0 if result.wasSuccessful() else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--models-bundle', type=Path, help='Use a local model release ZIP after verifying its outer SHA-256')
    parser.add_argument('--runtime-cache', type=Path, default=ROOT / '.private/downloads/runtime',
                        help='Directory of official runtime downloads, reused only after size/SHA verification')
    parser.add_argument('--runtime-only', action='store_true', help='Restore six runtime inputs and notices without models')
    parser.add_argument('--self-test', action='store_true', help='Run synthetic offline checks only')
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        require(not (args.runtime_only and args.models_bundle), '--runtime-only cannot be combined with --models-bundle')
        restore_runtime(ROOT, args.runtime_cache)
        if not args.runtime_only:
            restore_models(ROOT, args.models_bundle)
        print('Pinned inputs restored. No signing keys were read or created.')
        return 0
    except (RestoreError, OSError, ValueError, KeyError, zipfile.BadZipFile) as failure:
        # Avoid echoing signed redirect URLs, credentials or arbitrary response bodies.
        detail = str(failure) if isinstance(failure, RestoreError) else type(failure).__name__
        print('Restore stopped: ' + detail, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
