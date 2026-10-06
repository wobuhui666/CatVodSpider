#!/usr/bin/env python3
"""Build one reusable Snippet with a reviewed, exact-host allowlist."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit

BASE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--hosts', type=Path, default=BASE / 'hosts.json')
parser.add_argument('--output', type=Path, default=BASE / 'dist' / 'kazumi-proxy.js')
args = parser.parse_args()
hosts = json.loads(args.hosts.read_text())
for host, options in hosts.items():
    parsed = urlsplit('https://' + host)
    if parsed.hostname != host or parsed.netloc != host or '.' not in host or ':' in host or any(c.isspace() for c in host):
        raise SystemExit('Host manifest contains an invalid bare hostname')
    if not isinstance(options, dict) or set(options) - {'http'}:
        raise SystemExit('Host options may only contain the explicit http opt-in')
policy = {
    'path': '/kazumi',
    'maxRedirects': 5,
    'userAgent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36',
    'hosts': dict(sorted(hosts.items())),
}
source = (BASE / 'snippet.template.mjs').read_text()
assert source.count('__KAZUMI_POLICY_JSON__') == 1
output = source.replace('__KAZUMI_POLICY_JSON__', json.dumps(policy, ensure_ascii=True, separators=(',', ':'))).encode()
if len(output) > 32 * 1024:
    raise SystemExit('Snippet exceeds Cloudflare 32 KiB package limit')
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_bytes(output)
metadata = {'bytes': len(output), 'sha256': hashlib.sha256(output).hexdigest(), 'hosts': sorted(hosts), 'path': policy['path'], 'subrequestsPerInvocation': 1}
args.output.with_suffix('.metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps({'output': str(args.output), 'bytes': len(output), 'hosts': len(hosts), 'sha256': metadata['sha256']}))
