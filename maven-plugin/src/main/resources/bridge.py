"""Maven transport only: preserve the original scanner result without rule changes."""
import argparse
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pom', required=True, type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--to-version', default='3.0.13')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        from scanner import scan, render_text
        report = scan(args.pom, args.source, to_version=args.to_version)
        count = len(report['findings'])
        payload = {'schema_version': '1', 'status': 'ok',
                   'summary': {'hit_count': count, 'unknown_count': len(report['unknown'])},
                   'repair': {'request_file': 'repair-request.md', 'contact': 'unknown',
                              'offering': 'paid compatibility patch/PR pilot'}, 'scan': report}
        text = render_text(report) + '\nRepair entry: repair-request.md (paid compatibility patch/PR pilot; contact unknown).\n'
        request = ('# Compatibility patch / PR pilot request\n\n'
                   'Optional paid pilot; no request is sent by this tool. Contact: unknown (not published).\n'
                   'Review and redact reports before sharing with a maintainer you choose.\n\n'
                   'Requested scope: reproduce selected behavior differences and deliver a reviewable compatibility patch/PR.\n'
                   'Acceptance: baseline / upgraded / patched runtime evidence; scope and price agreed first.\n\n'
                   'Selected drift IDs:\n' + ''.join('- ' + item['id'] + '\n' for item in report['findings']) +
                   '\nRepository/contact: [fill locally]\nExpected behavior: [fill locally]\n')
        (args.output / 'repair-request.md').write_text(request, encoding='utf-8')
        code = 3 if count else 0
    except Exception as error:
        payload = {'schema_version': '1', 'status': 'error', 'summary': {'hit_count': 'unknown'},
                   'error': str(error)}
        text = 'Compat Foundry scan unavailable; hit count: unknown\n' + str(error) + '\n'
        (args.output / 'repair-request.md').write_text('Scan unavailable; repair scope: unknown. No request sent.\n', encoding='utf-8')
        code = 2
    (args.output / 'report.json').write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    (args.output / 'report.txt').write_text(text, encoding='utf-8')
    print(text, end='')
    return code


if __name__ == '__main__':
    sys.exit(main())
