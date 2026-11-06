#!/usr/bin/env python3
"""Read-only HTTP boundary check while the isolated Minecraft test client is running.

Only requests tools/list and ping; it never runs a game tool or changes a world. Pass --port when the
test client logged a different port (its config's mcpPort, or the next free port after a collision).
"""
import argparse
import http.client
import json
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--wait', type=int, default=0, help='Seconds to wait for the test client, up to 60')
parser.add_argument('--port', type=int, default=25590, help='The port the test client logged for its MCP server')
args = parser.parse_args()
PORT = args.port
MAX_BODY = 1 << 20
if not 0 <= args.wait <= 60:
    parser.error('--wait must be between 0 and 60')

payload = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}).encode()


def request(headers=None, body=payload):
    connection = http.client.HTTPConnection('127.0.0.1', PORT, timeout=3)
    try:
        connection.request('POST', '/mcp', body, headers or {'Content-Type': 'application/json'})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


deadline = time.monotonic() + args.wait
while True:
    try:
        code, body = request()
        assert code == 200, 'native client refused'
        names = {tool['name'] for tool in json.loads(body)['result']['tools']}
        assert names == {'minecraft_status', 'minecraft_nearby_blocks', 'minecraft_say', 'minecraft_run_command'}, 'not the expected Minecraft endpoint'
        break
    except OSError:
        if time.monotonic() >= deadline:
            raise SystemExit('Start the isolated Minecraft test client first.')
        time.sleep(.1)

for origin in ['http://localhost.attacker.example', 'http://127.0.0.1.attacker.example', 'http://localhost:3000', 'null', '']:
    assert request({'Origin': origin})[0] == 403, 'browser origin accepted'
for host in [f'attacker.example:{PORT}', f'localhost.attacker.example:{PORT}', f'127.0.0.1.attacker.example:{PORT}', f'localhost:{PORT + 1}', f'user@localhost:{PORT}']:
    assert request({'Host': host})[0] == 403, 'noncanonical Host accepted'


def rpc_error(response, status, code):
    got_status, body = response
    error = json.loads(body)['error']
    assert got_status == status and error['code'] == code, f'expected {status}/{code}, got {got_status}/{error}'


rpc_error(request(body=b'{bad'), 400, -32700)
for body in [b'null', b'{"id":1}', b'[]', b'{"id":1,"method":7}']:
    rpc_error(request(body=body), 400, -32600)
rpc_error(request(body=json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'nope'}).encode()), 200, -32601)
rpc_error(request(body=json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {}}).encode()), 200, -32602)
rpc_error(request(body=b'x' * (MAX_BODY + 1)), 413, -32700)
assert request(body=payload + b' ' * (MAX_BODY - len(payload)))[0] == 200, 'valid maximum body refused'
assert request({'Host': f'localhost:{PORT}', 'Content-Type': 'application/json'})[0] == 200, 'native localhost refused'
print('PASS actual Minecraft MCP: native tools/list works, browser origins and noncanonical Hosts refused, malformed/oversized requests get JSON-RPC errors, endpoint remains healthy')
