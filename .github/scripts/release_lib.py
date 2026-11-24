#!/usr/bin/env python3
"""Release helpers. Everything user-controlled (notes) arrives through environment variables or files, never through shell interpolation.

Sub-commands (used by .github/workflows/release.yml):
  next-version   -> prints VERSION_CODE / VERSION_NAME lines (monotonic across latest.json, versions.json and tags)
  metadata       -> writes out/latest.json + out/notes.md from the VERIFIED apk
  check          -> re-validates out/latest.json against the apk and the pinned certificate
  history        -> merges out/latest.json into <repo>/versions.json + CHANGELOG.md (never rewrites old entries)
"""
import datetime, hashlib, json, os, re, sys

HEX64 = re.compile(r'^[0-9a-f]{64}$')


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def max_published_code(latest, versions, tag_names):
    """Highest versionCode ever published (0 when nothing was). Tags only raise the floor (name = major.minor.<code-1>)."""
    codes = [0]
    if latest: codes.append(int(latest['versionCode']))
    for v in versions or []: codes.append(int(v['versionCode']))
    for t in tag_names or []:
        m = re.fullmatch(r'v\d+\.\d+\.(\d+)', t)
        if m: codes.append(int(m.group(1)) + 1)
    return max(codes)


def next_version(props, published_max):
    code = max(published_max + 1, int(props['VERSION_CODE']))
    return code, f"{props['VERSION_MAJOR']}.{props['VERSION_MINOR']}.{code - 1}"


def clean_notes(s, limit=4000):
    s = (s or '').replace('\r\n', '\n')
    s = ''.join(ch for ch in s if ch == '\n' or ch == '\t' or ord(ch) >= 32)
    return s.strip()[:limit]


def build_metadata(app_id, owner, repo, version_name, version_code, apk_path, cert, min_code, mandatory, notes_en, notes_ar, now=None):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    tag = f"v{version_name}"
    return {
        'applicationId': app_id,
        'versionName': version_name,
        'versionCode': int(version_code),
        'apkUrl': f"https://github.com/{owner}/{repo}/releases/download/{tag}/ludoai-{tag}.apk",
        'sha256': sha256_file(apk_path),
        'sizeBytes': os.path.getsize(apk_path),
        'certSha256': cert,
        'minVersionCode': int(min_code),
        'mandatory': bool(mandatory),
        'publishedAt': now.strftime('%Y-%m-%dT%H:%M:%SZ'),
        'releaseNotes': {'en': clean_notes(notes_en), 'ar': clean_notes(notes_ar)},
    }


def validate_metadata(meta, apk_path, pinned_cert, app_id, previous_code, owner, repo):
    """Returns a list of problems; empty means the metadata is exactly what the verified APK implies."""
    p = []
    if meta.get('applicationId') != app_id: p.append('applicationId')
    if not HEX64.match(meta.get('certSha256', '')): p.append('certSha256 format')
    if meta.get('certSha256') != pinned_cert: p.append('certSha256 != pinned')
    if meta.get('sha256') != sha256_file(apk_path): p.append('sha256 != apk')
    if meta.get('sizeBytes') != os.path.getsize(apk_path): p.append('size != apk')
    if not isinstance(meta.get('versionCode'), int) or meta['versionCode'] <= previous_code: p.append('versionCode not increasing')
    if not isinstance(meta.get('minVersionCode'), int) or not (1 <= meta['minVersionCode'] <= meta.get('versionCode', 0)): p.append('minVersionCode')
    if not isinstance(meta.get('mandatory'), bool): p.append('mandatory')
    tag = f"v{meta.get('versionName')}"
    if meta.get('apkUrl') != f"https://github.com/{owner}/{repo}/releases/download/{tag}/ludoai-{tag}.apk": p.append('apkUrl')
    if not meta.get('releaseNotes', {}).get('en'): p.append('notes.en empty')
    return p


def merge_history(hist, new):
    """Append-only: an existing versionCode may only be re-merged if it is byte-identical; codes must increase."""
    hist = list(hist or [])
    for h in hist:
        if h['versionCode'] == new['versionCode']:
            if h['sha256'] != new['sha256']: raise ValueError('versionCode already published with a different APK')
            return hist
    if hist and new['versionCode'] <= max(h['versionCode'] for h in hist):
        raise ValueError('versionCode is not greater than the history')
    return hist + [new]


def parse_badging(text):
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text)
    return {
        'package': pkg.group(1) if pkg else None,
        'versionCode': int(pkg.group(2)) if pkg else None,
        'versionName': pkg.group(3) if pkg else None,
        'debuggable': 'application-debuggable' in text,
    }


def _env(n, default=None):
    v = os.environ.get(n, default)
    if v is None: sys.exit(f'missing env {n}')
    return v


def main(argv):
    cmd = argv[1]
    if cmd == 'next-version':
        props = dict(l.strip().split('=', 1) for l in open('version.properties') if '=' in l and not l.startswith('#'))
        j = lambda n: json.load(open(os.environ[n])) if os.path.exists(os.environ.get(n, '')) else None
        mx = max_published_code(j('LATEST_JSON'), j('VERSIONS_JSON'), [t for t in open(os.environ['TAGS_FILE']).read().split()] if os.path.exists(os.environ.get('TAGS_FILE', '')) else [])
        code, name = next_version(props, mx)
        print(f'VERSION_CODE={code}\nVERSION_NAME={name}\nREMOTE_CODE={mx}')
    elif cmd == 'metadata':
        apk = _env('APK_PATH')
        meta = build_metadata(_env('APP_ID'), _env('UPDATES_OWNER'), _env('UPDATES_REPO'), _env('VERSION_NAME'), _env('VERSION_CODE'), apk,
                              _env('CERT_SHA256'), _env('MIN_VERSION_CODE', '1'), _env('MANDATORY', 'false') == 'true', _env('NOTES_EN'), _env('NOTES_AR', ''))
        os.makedirs('out', exist_ok=True)
        json.dump(meta, open('out/latest.json', 'w'), ensure_ascii=False, indent=2)
        open('out/notes.md', 'w').write(meta['releaseNotes']['en'] + ('\n\n' + meta['releaseNotes']['ar'] if meta['releaseNotes']['ar'] else '') + '\n')
        print('sha256', meta['sha256'], 'size', meta['sizeBytes'])
    elif cmd == 'check':
        meta = json.load(open('out/latest.json'))
        probs = validate_metadata(meta, _env('APK_PATH'), open('release/EXPECTED_CERT_SHA256').read().strip(), _env('APP_ID'), int(_env('REMOTE_CODE', '0')), _env('UPDATES_OWNER'), _env('UPDATES_REPO'))
        if probs: sys.exit('metadata invalid: ' + ', '.join(probs))
        print('metadata consistent with the verified APK')
    elif cmd == 'history':
        repo_dir = argv[2]
        new = json.load(open('out/latest.json'))
        p = os.path.join(repo_dir, 'versions.json')
        hist = json.load(open(p)) if os.path.exists(p) else []
        hist = merge_history(hist, new)
        json.dump(hist, open(p, 'w'), ensure_ascii=False, indent=2)
        json.dump(new, open(os.path.join(repo_dir, 'latest.json'), 'w'), ensure_ascii=False, indent=2)
        with open(os.path.join(repo_dir, 'CHANGELOG.md'), 'a') as f:
            f.write(f"\n## {new['versionName']} (code {new['versionCode']}) - {new['publishedAt']}\n\n{new['releaseNotes']['en']}\n\n{new['releaseNotes']['ar']}\n\nSHA-256: `{new['sha256']}`\n")
    elif cmd == 'badging':
        info = parse_badging(sys.stdin.read())
        exp_pkg, exp_code = _env('APP_ID'), int(_env('VERSION_CODE'))
        if info['package'] != exp_pkg: sys.exit(f"applicationId {info['package']} != {exp_pkg}")
        if info['versionCode'] != exp_code: sys.exit(f"versionCode {info['versionCode']} != {exp_code}")
        if info['versionName'] != _env('VERSION_NAME'): sys.exit(f"versionName {info['versionName']} != {_env('VERSION_NAME')}")
        if info['debuggable']: sys.exit('APK is debuggable')
        print('badging ok', info)
    else:
        sys.exit('unknown command ' + cmd)


if __name__ == '__main__':
    main(sys.argv)
