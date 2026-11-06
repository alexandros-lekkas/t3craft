#!/usr/bin/env python3
"""Two loopback-only T3 fixtures. No credentials, agent calls, or real repo writes.

Used by `featureCheck` and the `desk-review` Minecraft self-test.
"""
import argparse
import base64
import hashlib
import json
import socket
import struct
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PATCH = '''diff --git a/src/greeting.ts b/src/greeting.ts
index 1111111..2222222 100644
--- a/src/greeting.ts
+++ b/src/greeting.ts
@@ -1 +1,2 @@
-export const greeting = "hello";
+export const greeting = "hello, Minecraft";
+export const reviewed = true;
@@ -20,2 +21,2 @@
 unchanged context
-old footer
+new footer
diff --git a/obsolete.txt b/obsolete.txt
deleted file mode 100644
--- a/obsolete.txt
+++ /dev/null
@@ -1 +0,0 @@
-old text
'''


def thread(ident, waiting=False, question=False, older=False):
    now = '2026-10-04T00:00:00Z' if older else datetime.now(timezone.utc).isoformat()
    payload = {'requestId': 'shared-request', 'requestKind': 'Bash', 'detail': 'npm test -- --runInBand'}
    kind = 'approval.requested'
    if question:
        kind = 'user-input.requested'
        payload = {'requestId': 'shared-request', 'questions': [{'id': 'format', 'question': 'Which export format should we use?', 'options': [{'label': 'JSON'}, {'label': 'CSV'}], 'allowCustomAnswer': True}]}
    return {'id': ident, 'projectId': 'fixture-project', 'title': 'Review greeting changes' if not waiting else 'Export format' if question else 'Run greeting tests',
            'updatedAt': now, 'archivedAt': None, 'latestTurn': {'turnId': ident + '-turn', 'state': 'completed', 'requestedAt': now},
            'modelSelection': {'instanceId': 'fixture', 'model': 'fixture'}, 'runtimeMode': 'approval-required', 'interactionMode': 'default',
            'hasPendingApprovals': waiting and not question, 'hasPendingUserInput': waiting and question,
            'messages': [{'id': ident + '-reply', 'role': 'assistant', 'text': 'Updated the greeting and removed the obsolete file. Ready for your review.', 'streaming': False}, {'id': ident + '-newer-reply', 'role': 'assistant', 'text': 'A later unrelated reply.', 'streaming': False}],
            'activities': [{'kind': kind, 'createdAt': now, 'payload': payload}] if waiting else [{'kind': 'command.completed', 'summary': 'Fixture command completed (exit 1)', 'createdAt': now, 'payload': {'exitCode': 1}}],
            'checkpoints': [{'checkpointTurnCount': 1, 'status': 'ready', 'assistantMessageId': ident + '-reply', 'files': [{'path': 'src/greeting.ts'}, {'path': 'obsolete.txt'}]}]}


def shell_thread(value):
    pending = {'id': 'shared-request', 'kind': 'user_input' if value['hasPendingUserInput'] else 'command', 'createdAt': value['updatedAt']} if value['hasPendingApprovals'] or value['hasPendingUserInput'] else None
    return {**value, 'status': 'completed', 'activeRunId': None, 'latestRunId': value['id'] + '-turn', 'latestRunRequestedAt': value['updatedAt'], 'latestRunStartedAt': value['updatedAt'], 'pendingRuntimeRequest': pending}


def projection(value):
    ident = value['id']; run_id = ident + '-turn'; node_id = ident + '-node'
    requests = []; items = []
    if value['hasPendingApprovals'] or value['hasPendingUserInput']:
        question = value['activities'][0]['kind'] == 'user-input.requested'
        resolved = any(a['kind'].endswith('.resolved') for a in value['activities'])
        requests = [{'id': 'shared-request', 'nodeId': node_id, 'kind': 'user_input' if question else 'command', 'status': 'resolved' if resolved else 'pending', 'responseCapability': {'type': 'live', 'providerSessionId': 'fixture-session'}, 'createdAt': value['updatedAt']}]
        payload = value['activities'][0]['payload']
        items = [{'type': 'user_input_request' if question else 'approval_request', 'requestId': 'shared-request', 'nodeId': node_id, 'questions': payload.get('questions', []), 'prompt': payload.get('detail'), 'requestKind': 'command'}]
    items += [{'id': ident + '-command', 'type': 'command_execution', 'status': 'completed', 'input': 'npm test', 'exitCode': 1, 'startedAt': value['updatedAt'], 'ordinal': 2}, {'id': ident + '-unknown', 'type': 'future_tool', 'title': 'Future backend tool', 'status': 'running', 'startedAt': value['updatedAt'], 'ordinal': 3}]
    messages = [{**m, 'runId': run_id if i == 0 else 'unrelated-run', 'createdAt': value['updatedAt']} for i, m in enumerate(value['messages'])]
    return {'thread': value, 'runs': [{'id': run_id, 'ordinal': 1, 'status': 'completed', 'requestedAt': value['updatedAt'], 'startedAt': value['updatedAt']}], 'messages': messages, 'runtimeRequests': requests, 'turnItems': items,
            'checkpoints': [{'appRunOrdinal': 1, 'runId': run_id, 'capturedAt': value['updatedAt'], 'status': 'ready', 'files': value['checkpoints'][0]['files']}]}


class Fixture(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, port, machine, protocol2=False):
        super().__init__(('127.0.0.1', port), Handler)
        self.machine = machine
        self.protocol2 = protocol2
        self.lock = threading.RLock()
        self.dispatches = []
        self.connections = []
        self.subscribers = {}
        self.offline = False
        self.auth_failed = False
        self.protocol_version = 2 if self.protocol2 else 1
        self.reset()

    def reset(self):
        self.threads = {f'{self.machine}-done-{i}': thread(f'{self.machine}-done-{i}') for i in range(12)}
        self.threads[f'{self.machine}-waiting'] = thread(f'{self.machine}-waiting', True, self.machine == 'B', True)
        self.threads[f'{self.machine}-done-11']['projectId'] = 'fixture-alt'
        self.dispatches.clear()
        self.mcp_calls = []
        self.mcp_answered = False
        self.mcp_sent = False
        self.oauth_redirect = None
        self.oauth_challenge = None
        self.offline = False
        self.auth_failed = False
        self.protocol_version = 2 if self.protocol2 else 1

    def shell(self):
        return {'schemaVersion': 2 if self.protocol2 else 1, 'projects': [{'id': 'fixture-project', 'title': 'Greeting fixture'}, {'id': 'fixture-alt', 'title': 'Second project'}], 'threads': [shell_thread(t) if self.protocol2 else t for t in self.threads.values()]}


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_): pass

    def reply(self, value, code=200, headers=None):
        body = json.dumps(value).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        for key, value in (headers or {}).items(): self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        with self.server.lock:
            base = f'http://127.0.0.1:{self.server.server_port}'
            if self.path == '/.well-known/oauth-protected-resource/mcp': return self.reply({'resource': base + '/mcp', 'authorization_servers': [base]})
            if self.path == '/.well-known/oauth-authorization-server': return self.reply({'issuer': base, 'registration_endpoint': base + '/oauth/mcp/register', 'authorization_endpoint': base + '/oauth/mcp/authorize', 'token_endpoint': base + '/oauth/mcp/token'})
            if self.path == '/_qa/mcp-hybrid':
                self.server.threads['mcp-approval'] = thread('mcp-approval', True)
                self.server.threads['mcp-question'] = thread('mcp-question', True, True)
                return self.reply({'ok': True})
            if self.path == '/_qa/mcp-calls': return self.reply(self.server.mcp_calls)
            if self.path.startswith('/oauth/mcp/authorize?'):
                q = parse_qs(urlparse(self.path).query)
                assert q['code_challenge_method'] == ['S256']
                assert q['redirect_uri'] == [self.server.oauth_redirect]
                assert q['resource'] == [base + '/mcp']
                self.server.oauth_challenge = q['code_challenge'][0]
                return self.reply({'redirect': self.server.oauth_redirect, 'state': q['state'][0], 'code': 'fixture-code', 'iss': base})
            if self.path == '/.well-known/t3/environment': return self.reply({'label': 'Fixture ' + self.server.machine, 'orchestrationProtocolVersion': self.server.protocol_version})
            if self.path in ('/_qa/protocol3', '/_qa/auth-failed'):
                if self.path == '/_qa/protocol3': self.server.protocol_version = 3
                else: self.server.auth_failed = True
                for connection in list(self.server.connections):
                    try: connection.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
                return self.reply({'ok': True})
            if self.path == '/_qa/dispatches': return self.reply(self.server.dispatches)
            if self.path == '/_qa/reset': self.server.reset(); self.broadcast_snapshot(); return self.reply({'ok': True})
            if self.path == '/_qa/empty': self.server.threads.clear(); self.broadcast_snapshot(); return self.reply({'ok': True})
            if self.path == '/_qa/collision':
                self.server.threads['A-done-0'] = thread('A-done-0')
                self.broadcast_snapshot()
                return self.reply({'ok': True})
            if self.path == '/_qa/churn':
                self.server.threads[f'{self.server.machine}-done-0']['updatedAt'] = '2020-01-01T00:00:00Z'
                for i in range(6):
                    ident = f'{self.server.machine}-new-{i}'
                    self.server.threads[ident] = thread(ident)
                self.broadcast_snapshot()
                return self.reply({'ok': True})
            if self.path == '/_qa/archive-pin':
                target = self.server.threads[f'{self.server.machine}-done-0']
                target['archivedAt'] = datetime.now(timezone.utc).isoformat()
                self.broadcast_snapshot()
                return self.reply({'ok': True})
            if self.path == '/_qa/offline':
                self.server.offline = True
                for connection in list(self.server.connections):
                    try: connection.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
                return self.reply({'ok': True})
            if self.path == '/_qa/resolve':
                target = self.server.threads[f'{self.server.machine}-waiting']
                target['activities'].append({'kind': 'user-input.resolved' if self.server.machine == 'B' else 'approval.resolved', 'payload': {'requestId': 'shared-request'}})
                # Deliberately retain stale shell flags: response code must revalidate activities.
                return self.reply({'ok': True})
            if self.server.offline: return self.reply({'message': 'Fixture offline'}, 503)
            if self.server.auth_failed: return self.reply({'message': 'Device access denied'}, 401)
            if self.path.startswith('/api/orchestration/') and self.headers.get('x-t3-orchestration-protocol') != str(self.server.protocol_version): return self.reply({'message': 'Wrong protocol header'}, 400)
            if self.path.startswith('/ws?'):
                if self.server.protocol2 and 'orchestrationProtocol=2' not in self.path: return self.reply({'message': 'Missing websocket protocol'}, 400)
            elif self.path == '/api/orchestration/shell': return self.reply(self.server.shell())
            elif self.path.startswith('/api/orchestration/threads/'):
                if self.server.protocol2 != self.path.endswith('/bounded'): return self.reply({'message': 'Wrong thread endpoint'}, 404)
                ident = self.path.split('/threads/')[1].split('?')[0].split('/')[0]
                value = self.server.threads[ident]
                return self.reply({'projection': projection(value)} if self.server.protocol2 else {'thread': value})
            else: return self.reply({'message': 'Unknown route'}, 404)
        self.websocket()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path == '/mcp': return self.mcp(json.loads(body))
        if self.path == '/oauth/mcp/register':
            self.server.oauth_redirect = json.loads(body)['redirect_uris'][0]
            assert self.server.oauth_redirect.startswith('http://127.0.0.1:')
            return self.reply({'client_id': 'fixture-client'})
        if self.path == '/oauth/mcp/token':
            q = parse_qs(body.decode())
            challenge = base64.urlsafe_b64encode(hashlib.sha256(q['code_verifier'][0].encode()).digest()).decode().rstrip('=')
            assert challenge == self.server.oauth_challenge
            assert q['code'] == ['fixture-code'] and q['redirect_uri'] == [self.server.oauth_redirect]
            return self.reply({'access_token': 'mcp-fixture', 'expires_in': 3600, 'token_type': 'Bearer'})
        if self.path == '/oauth/token' and parse_qs(body.decode()).get('subject_token', [''])[0] == 'delayed-fixture': time.sleep(3)
        with self.server.lock:
            if self.server.offline: return self.reply({'message': 'Fixture offline'}, 503)
            if self.path == '/oauth/token':
                token = parse_qs(body.decode()).get('subject_token', [''])[0]
                if token == 'expired-fixture': return self.reply({'message': 'Do not expose this credential: ' + token}, 401)
                if token not in ('fixture-pairing', 'delayed-fixture'): return self.reply({'message': 'Invalid token: ' + token}, 400)
                self.server.auth_failed = False
                return self.reply({'access_token': 'fixture-only', 'expires_in': 3600})
            if self.server.auth_failed: return self.reply({'message': 'Device access denied'}, 401)
            if self.path == '/api/auth/websocket-ticket': return self.reply({'ticket': 'fixture-only'})
            if self.path == '/api/orchestration/dispatch':
                command = json.loads(body)
                self.dispatch(command)
                return self.reply({'sequence': len(self.server.dispatches)})
        self.reply({'message': 'Unknown route'}, 404)

    def mcp(self, request):
        auth = self.headers.get('Authorization')
        if auth not in ('Bearer mcp-fixture', 'Bearer mcp-readonly'):
            return self.reply({'error': 'Credential must never be echoed: ' + str(auth)}, 401)
        assert self.headers.get('MCP-Protocol-Version') == '2025-06-18'
        method = request['method']
        if method == 'initialize':
            return self.reply({'jsonrpc': '2.0', 'id': request['id'], 'result': {'protocolVersion': '2025-06-18', 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'fixture', 'version': '1'}}}, headers={'Mcp-Session-Id': 'mcp-session'})
        assert self.headers.get('Mcp-Session-Id') == 'mcp-session'
        if method == 'notifications/initialized': return self.reply({}, 202)
        assert method == 'tools/call'
        name, args = request['params']['name'], request['params']['arguments']
        self.server.mcp_calls.append({'name': name, 'arguments': args})
        if auth == 'Bearer mcp-readonly' and (name in ('t3_thread_send', 't3_thread_launch', 't3_thread_interrupt') or 'answers' in args or 'modelSelection' in args):
            return self.reply({'error': 'Read-only'}, 403)
        ident = args.get('threadId', 'mcp-question')
        now = '2026-11-01T00:00:00Z'
        row = {'threadId': ident, 'projectId': 'mcp-project', 'title': 'MCP fixture', 'status': 'completed' if self.server.mcp_answered else 'waiting', 'latestRunId': 'mcp-run', 'providerInstanceId': 'fixture-provider', 'model': 'fixture-model', 'runtimeMode': 'full-access', 'interactionMode': 'default', 'updatedAt': now}
        questions = [{'id': 'format', 'header': 'Format', 'question': 'Which format?', 'options': [{'label': 'JSON', 'description': 'Structured', 'value': 'json'}], 'allowCustomAnswer': True}]
        if name == 't3_thread_list':
            assert args['allProjects'] is True
            cursor = args.get('cursor', 0)
            row['threadId'] = 'mcp-question' if cursor == 0 else 'mcp-approval'
            row['status'] = 'waiting' if cursor else row['status']
            result = {'threads': [row], 'nextCursor': 1 if cursor == 0 else None, 'total': 2}
        elif name == 't3_project_list': result = {'projects': [{'id': 'mcp-project', 'title': 'MCP project'}], 'nextCursor': None}
        elif name == 't3_thread_read':
            row['worktreePath'] = '/fixture/worktree'
            row['branch'] = 'fixture-branch'
            if args.get('view') == 'activity':
                if 'afterPosition' not in args:
                    items = [{'position': 1, 'itemId': 'message-1', 'type': 'assistant_message', 'text': 'Sent' if self.server.mcp_sent else 'MCP reply', 'status': 'completed', 'updatedAt': now, 'textTruncated': False}]
                    more = True
                else:
                    assert args['afterPosition'] == 1
                    items = [{'position': 2, 'itemId': 'tool-1', 'type': 'command_execution', 'title': 'Fixture command', 'text': 'echo fixture', 'status': 'completed', 'updatedAt': now, 'textTruncated': False}]
                    more = False
            else: items, more = [], False
            result = {'thread': row, 'recentRuns': [], 'items': items, 'hasMore': more, 'nextPosition': 1 if more else 2}
        elif name == 't3_pending_request':
            action = args['action']
            if action == 'list': result = {'requestIds': ['mcp-request'] if ident == 'mcp-question' and not self.server.mcp_answered else []}
            elif action == 'read': result = {'requestId': 'mcp-request', 'questions': questions}
            else:
                assert args['requestId'] == 'mcp-request' and args['answers'] == {'format': 'json'}
                self.server.mcp_answered = True
                result = {'sequence': 1}
        elif name == 'orchestrator_capabilities': result = {'providers': [{'providerInstanceId': 'fixture-provider', 'driverKind': 'codex', 'displayName': 'Fixture', 'models': [{'id': 'fixture-model', 'label': 'Model'}], 'constraints': []}]}
        elif name == 't3_thread_configure': result = {'sequence': 2}
        elif name == 't3_thread_send':
            assert args['mode'] == 'queue' and args['message'] == 'Fixture prompt' and args['clientRequestId']
            self.server.mcp_sent = True
            result = {'threadId': ident, 'delivery': 'queued'}
        elif name == 't3_thread_launch':
            assert args['workspaceStrategy'] == {'type': 'existing_worktree', 'worktreePath': '/fixture/worktree'}
            assert args['projectId'] == 'mcp-project' and args['clientRequestId']
            result = {'threadId': 'mcp-new'}
        elif name == 't3_thread_interrupt': result = {'threadId': ident}
        else: raise AssertionError('Unknown MCP tool: ' + name)
        reply = {'jsonrpc': '2.0', 'id': request['id'], 'result': {'content': [{'type': 'text', 'text': json.dumps(result)}]}}
        # Exercise both Streamable HTTP response formats and both tool-result encodings.
        if name == 't3_thread_read':
            payload = ('event: message\ndata: ' + json.dumps(reply) + '\n\n').encode()
            self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)
        else:
            reply['result']['structuredContent'] = result
            self.reply(reply)

    def broadcast_snapshot(self):
        for handler, ident in list(self.server.subscribers.items()):
            try: handler.frame({'_tag': 'Chunk', 'requestId': ident, 'values': [{'kind': 'snapshot', 'snapshot': self.server.shell()}]})
            except OSError: pass

    def dispatch(self, command):
        self.server.dispatches.append(command)
        target = self.server.threads[command['threadId']]
        target['activities'].append({'kind': 'approval.resolved' if command.get('decision') else 'user-input.resolved', 'payload': {'requestId': command['requestId']}})
        target['hasPendingApprovals'] = target['hasPendingUserInput'] = False
        for handler, ident in list(self.server.subscribers.items()):
            try: handler.frame({'_tag': 'Chunk', 'requestId': ident, 'values': [{'kind': 'thread.updated' if self.server.protocol2 else 'thread-upserted', 'thread': shell_thread(target) if self.server.protocol2 else target}]})
            except OSError: pass

    def frame(self, obj):
        data = json.dumps(obj).encode()
        length = len(data)
        header = bytes([0x81, length]) if length < 126 else bytes([0x81, 126]) + struct.pack('!H', length) if length < 65536 else bytes([0x81, 127]) + struct.pack('!Q', length)
        with self.frame_lock:
            self.wfile.write(header + data)
            self.wfile.flush()

    def websocket(self):
        self.frame_lock = threading.Lock()
        key = self.headers['Sec-WebSocket-Key']
        accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101)
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', accept)
        self.end_headers()
        self.server.connections.append(self.connection)
        self.close_connection = True
        try:
            while True:
                header = self.rfile.read(2)
                if len(header) != 2 or header[0] & 15 == 8: break
                length = header[1] & 127
                if length == 126: length = struct.unpack('!H', self.rfile.read(2))[0]
                elif length == 127: length = struct.unpack('!Q', self.rfile.read(8))[0]
                mask = self.rfile.read(4) if header[1] & 128 else None
                data = self.rfile.read(length)
                if mask: data = bytes(v ^ mask[i % 4] for i, v in enumerate(data))
                request = json.loads(data)
                if request.get('_tag') == 'Ping': self.frame({'_tag': 'Pong'}); continue
                if request.get('_tag') != 'Request': continue
                ident, tag = request['id'], request['tag']
                if tag == 'orchestration.subscribeShell':
                    self.server.subscribers[self] = ident
                    self.frame({'_tag': 'Chunk', 'requestId': ident, 'values': [{'kind': 'snapshot', 'snapshot': self.server.shell()}, {'kind': 'synchronized'}]})
                    if self.server.protocol2:
                        metadata = {'kind': 'snapshot', 'snapshot': {'projects': self.server.shell()['projects'], 'threads': [], 'archivedThreads': []}, 'resolvedRepositoryIdentityRoots': ['/fixture']}
                        self.frame({'_tag': 'Chunk', 'requestId': ident, 'values': [metadata]})
                elif tag == 'orchestration.subscribeThread': pass
                elif tag == 'orchestration.dispatchCommand':
                    command = request['payload']
                    if command['type'] != 'runtime-request.respond': raise ValueError('Unexpected command')
                    with self.server.lock: self.dispatch(command)
                    self.frame({'_tag': 'Exit', 'requestId': ident, 'exit': {'_tag': 'Success', 'value': {'sequence': len(self.server.dispatches)}}})
                else:
                    value = {'providers': []} if tag == 'server.getConfig' else {'threadId': request['payload']['threadId'], 'fromTurnCount': 0, 'toTurnCount': 1, 'diff': PATCH}
                    self.frame({'_tag': 'Exit', 'requestId': ident, 'exit': {'_tag': 'Success', 'value': value}})
        except (OSError, ValueError): pass
        finally:
            self.server.subscribers.pop(self, None)
            if self.connection in self.server.connections: self.server.connections.remove(self.connection)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=25680)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--protocol2', action='store_true')
    args = parser.parse_args()
    servers = [Fixture(args.port, 'A', args.protocol2), Fixture(args.port + 1, 'B', args.protocol2)]
    if args.config:
        args.config.parent.mkdir(parents=True, exist_ok=True)
        args.config.write_text(json.dumps({'environments': [{'label': 'Fixture ' + s.machine, 'baseUrl': f'http://127.0.0.1:{s.server_port}', 'accessToken': 'fixture-only'} for s in servers], 'threadId': 'A-done-0', 'books': False}))
        args.config.chmod(0o600)
    for server in servers: threading.Thread(target=server.serve_forever, daemon=True).start()
    print('Loopback T3 fixtures ready:', args.port, args.port + 1, flush=True)
    threading.Event().wait()
