#!/usr/bin/env python3
"""Release-time secret / PII scanner for this repo.

Usage:
  python3 tools/sanitize.py --root software --out /tmp/scan.json
  python3 tools/sanitize.py --root software --redact --out /tmp/scan.json

Exit codes: 0 = clean, 2 = findings at/above --fail-on threshold.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

SKIP_DIRS = {'.git', 'node_modules', 'build', '.gradle', 'dist', '__pycache__',
             '.venv', 'venv', 'apk_extracts', '.idea', '.cache'}
TEXT_EXT = {'.java', '.kt', '.kts', '.gradle', '.xml', '.json', '.yml', '.yaml',
            '.md', '.txt', '.py', '.sh', '.js', '.ts', '.html', '.css', '.cfg',
            '.ini', '.toml', '.properties', '.service', '.env', '.conf'}

RULES = [
    ('private_ipv4', 'HIGH', r'(?<![0-9.])(?:10[.][0-9]{1,3}|192[.]168|172[.](?:1[6-9]|2[0-9]|3[01]))[.][0-9]{1,3}[.][0-9]{1,3}(?![0-9.])', '<LAN_IP>'),
    ('tailscale_ip', 'HIGH', r'(?<![0-9.])100[.](?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])[.][0-9]{1,3}[.][0-9]{1,3}(?![0-9.])', '<TS_IP>'),
    ('url_token',   'HIGH', r'https?://[^\s\x22\x27]*[?&](?:token|key|sig|access_token|auth)=[^\s\x22\x27&]*', '<URL_WITH_TOKEN>'),
    ('bearer',      'HIGH', r'(?i)bearer\s+[A-Za-z0-9._~+/=-]{20,}', 'Bearer <TOKEN>'),
    ('secret_json', 'HIGH', r'(?i)(\x22?[A-Za-z0-9_]*(?:token|secret|password|passwd|api_?key|appkey|access_key)\x22?\s*[:=]\s*)\x22([^\x22\n<]{8,})\x22', '\g<1>"<SECRET>"'),
    ('secret_bare', 'HIGH', r'\b(TOKEN|SECRET|API_KEY|PASSWORD|ACCESS_KEY)\s*=[\s]*([A-Za-z0-9_\-]{16,})', '\g<1> = <SECRET>'),
    ('serial_json', 'HIGH', r'(?i)(\x22?(?:sn|serial|serial_?no|device_?sn)\x22?\s*[:=]\s*)\x22?([A-Z0-9]{10,24})(?=[\x22\s,}])\x22?', '\g<1>"<SN>"'),
    ('mac',          'MED',  r'(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])', '<MAC>'),
    ('home_path',    'MED',  r'/home/(?!user|runner|<)[A-Za-z0-9_.-]+', '/home/<USER>'),
    ('email',        'MED',  r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}', '<EMAIL>'),
    ('phone_cn',     'MED',  r'(?<![0-9])1[3-9][0-9]{9}(?![0-9])', '<PHONE>'),
    ('private_domain', 'MED', r'\b[a-z0-9-]{2,}[.](?:vip|lan|internal)\b', '<PRIVATE_DOMAIN>'),
    ('ssid',         'MED',  r'(?i)\bssid\b\s*[:=]\s*[\x22\x27]?([^\s\x22\x27,]{2,32})', '<SSID>'),
    ('gps',          'MED',  r'(?i)\b(?:lat|lon|lng|latitude|longitude)\b\s*[:=]\s*-?[0-9]{1,3}[.][0-9]{3,}', '<COORD>'),
    ('vin',          'LOW',  r'(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])', '<VIN?>'),
]
COMPILED = [(rid, sev, re.compile(rx), ph) for rid, sev, rx, ph in RULES]


def iter_files(root, max_bytes):
    for dirpath, dirnames, filenames in os.walk(Path(root)):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            p = Path(dirpath) / name
            if p.suffix.lower() not in TEXT_EXT and not name.startswith('.'):
                continue
            try:
                if p.stat().st_size > max_bytes:
                    continue
            except OSError:
                continue
            yield p


def scan_file(path):
    try:
        text = path.read_text(encoding='utf-8', errors='ignore')
    except OSError:
        return []
    out = []
    for i, line in enumerate(text.splitlines(), 1):
        if len(line) > 4000:
            continue
        for rid, sev, rx, ph in COMPILED:
            m = rx.search(line)
            if m:
                out.append({'rule': rid, 'severity': sev, 'line': i,
                            'match': m.group(0)[:160], 'placeholder': ph})
    return out


def redact_text(text):
    hits = 0
    for rid, sev, rx, ph in COMPILED:
        text, n = rx.subn(ph, text)
        hits += n
    return text, hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', action='append', required=True)
    ap.add_argument('--out', default='')
    ap.add_argument('--redact', action='store_true')
    ap.add_argument('--max-bytes', type=int, default=5 * 1024 * 1024)
    ap.add_argument('--fail-on', choices=['HIGH', 'MED', 'LOW'], default='HIGH')
    ap.add_argument('--quiet', action='store_true')
    args = ap.parse_args()
    findings, redacted, scanned = [], 0, 0
    for root in args.root:
        for p in iter_files(root, args.max_bytes):
            scanned += 1
            hits = scan_file(p)
            if not hits:
                continue
            findings.append({'file': str(p), 'hits': hits})
            if args.redact:
                txt = p.read_text(encoding='utf-8', errors='ignore')
                new, n = redact_text(txt)
                if n and new != txt:
                    p.write_text(new, encoding='utf-8')
                    redacted += n
    counts = {'HIGH': 0, 'MED': 0, 'LOW': 0}
    for f in findings:
        for h in f['hits']:
            counts[h['severity']] = counts.get(h['severity'], 0) + 1
    result = {'scanned_files': scanned, 'files_with_findings': len(findings),
              'counts': counts, 'redacted_hits': redacted, 'findings': findings}
    if args.out:
        Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    if not args.quiet:
        print(json.dumps({k: result[k] for k in ('scanned_files', 'files_with_findings', 'counts', 'redacted_hits')}, ensure_ascii=False))
    return 2 if counts.get(args.fail_on, 0) > 0 else 0


if __name__ == '__main__':
    sys.exit(main())