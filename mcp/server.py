#!/usr/bin/env python3
"""Local stdio MCP entry point. No HTTP, telemetry, sample guessing or writes."""
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scanner'))
from scanner import scan

TOOL = {'name': 'scan_spring_boot_drift',
        'description': 'Offline Spring Boot 2.7 to 3.0 static behavior risks, fixes, official sources and unknowns. Local paths only.',
        'inputSchema': {'type': 'object', 'required': ['pom'], 'additionalProperties': False,
                        'properties': {'pom': {'type': 'string'}, 'source': {'type': 'string'},
                                       'to_version': {'type': 'string', 'default': '3.0.13'}}}}


def dispatch(request):
    if not isinstance(request, dict) or request.get('jsonrpc') != '2.0' or not isinstance(request.get('method'), str):
        return {'jsonrpc': '2.0', 'id': request.get('id') if isinstance(request, dict) else None,
                'error': {'code': -32600, 'message': 'Invalid Request'}}
    if 'id' not in request:
        return None
    reply = {'jsonrpc': '2.0', 'id': request['id']}
    method, params = request['method'], request.get('params', {})
    if not isinstance(params, dict):
        reply['error'] = {'code': -32602, 'message': 'Invalid params'}
    elif method == 'initialize':
        reply['result'] = {'protocolVersion': '2024-11-05', 'capabilities': {'tools': {}},
                           'serverInfo': {'name': 'compat-foundry', 'version': '0.1.0'}}
    elif method == 'ping':
        reply['result'] = {}
    elif method == 'tools/list':
        reply['result'] = {'tools': [TOOL]}
    elif method == 'tools/call':
        if params.get('name') != TOOL['name']:
            reply['error'] = {'code': -32602, 'message': 'Unknown tool'}
        else:
            try:
                args = params.get('arguments', {})
                if not isinstance(args, dict) or not isinstance(args.get('pom'), str) or not args['pom']:
                    raise ValueError('pom is a required local file path')
                if set(args) - {'pom', 'source', 'to_version'} or any(not isinstance(v, str) for v in args.values()):
                    raise ValueError('Only string pom, source and to_version arguments are supported')
                report = scan(args['pom'], args.get('source'), to_version=args.get('to_version', '3.0.13'))
                reply['result'] = {'content': [{'type': 'text', 'text': json.dumps(report)}]}
            except Exception as error:
                reply['result'] = {'isError': True, 'content': [{'type': 'text', 'text': str(error)}]}
    else:
        reply['error'] = {'code': -32601, 'message': 'Method not found'}
    return reply


def main():
    for line in sys.stdin:
        try:
            reply = dispatch(json.loads(line))
        except (ValueError, RecursionError):
            reply = {'jsonrpc': '2.0', 'id': None, 'error': {'code': -32700, 'message': 'Parse error'}}
        if reply is not None:
            print(json.dumps(reply), flush=True)


if __name__ == '__main__':
    main()
