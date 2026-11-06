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
import uuid
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
        self.mcp_sessions = set()
        self.revoked = set()
        self.mcp_down = False
        self.oauth_access = "full-access"
        self.oauth_decision = "approve"
        self.mcp_violations = []
        self.mcp_posts = 0
        self.mcp_history_mode = None
        self.mcp_detail_error = False
        self.mcp_items = [
            {'position': 1, 'itemId': 'message-1', 'type': 'assistant_message', 'text': 'MCP reply', 'status': 'completed', 'updatedAt': '2026-11-01T00:00:00Z', 'textTruncated': False},
            {'position': 2, 'itemId': 'tool-1', 'type': 'command_execution', 'title': 'Fixture command', 'text': 'echo fixture', 'status': 'completed', 'updatedAt': '2026-11-01T00:00:00Z', 'textTruncated': False},
        ]
        self.visibility = False
        self.mcp_answered_ids = set()
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

    def reply_empty(self, code, headers=None):
        self.send_response(code)
        self.send_header('Content-Length', '0')
        for key, value in (headers or {}).items(): self.send_header(key, value)
        self.end_headers()

    def do_GET(self):
        with self.server.lock:
            base = f'http://127.0.0.1:{self.server.server_port}'
            if self.path == '/.well-known/oauth-protected-resource/mcp': return self.reply({'resource': base + '/mcp', 'authorization_servers': [base]})
            if self.path == '/.well-known/oauth-authorization-server': return self.reply({'issuer': base, 'registration_endpoint': base + '/oauth/mcp/register', 'authorization_endpoint': base + '/oauth/mcp/authorize', 'token_endpoint': base + '/oauth/mcp/token'})
            if self.path == '/_qa/visibility':
                self.server.visibility = True
                for name in ('settled', 'snoozed', 'subagent', 'sidechat', 'settled-needs', 'snoozed-needs', 'active-override', 'archived'):
                    ident = 'vis-' + name
                    value = thread(ident, name in ('subagent', 'sidechat', 'settled-needs', 'snoozed-needs'), True)
                    value['lineage'] = {'relationshipToParent': 'subagent' if name == 'subagent' else None, 'sideChat': name == 'sidechat'}
                    if name.startswith('settled'): value.update(settledOverride='settled', settledAt='2026-01-01T00:00:00Z')
                    if name.startswith('snoozed'): value.update(snoozedUntil='2099-01-01T00:00:00Z')
                    if name == 'active-override': value.update(settledOverride='active', settledAt='2026-01-01T00:00:00Z')
                    if name == 'archived': value['archivedAt'] = '2026-01-01T00:00:00Z'
                    self.server.threads[ident] = value
                self.broadcast_snapshot()
                return self.reply({'ok': True})
            if self.path == '/_qa/mcp-hybrid':
                self.server.threads['mcp-approval'] = thread('mcp-approval', True)
                self.server.threads['mcp-question'] = thread('mcp-question', True, True)
                return self.reply({'ok': True})
            if self.path.startswith('/_qa/mcp-item?'):
                q = parse_qs(urlparse(self.path).query)
                item = next(i for i in self.server.mcp_items if i['itemId'] == q['itemId'][0])
                item.update(status=q['status'][0], text=q['text'][0])
                return self.reply({'ok': True})
            if self.path in ('/_qa/mcp-detail-error', '/_qa/mcp-detail-ok'):
                self.server.mcp_detail_error = self.path.endswith('error')
                return self.reply({'ok': True})
            if self.path in ('/_qa/mcp-history-long', '/_qa/mcp-history-null'):
                self.server.mcp_history_mode = self.path.rsplit('-', 1)[1]; return self.reply({'ok': True})
            if self.path.startswith('/_qa/mcp-consent?'):
                q = parse_qs(urlparse(self.path).query)
                self.server.oauth_access = q.get('access', ['full-access'])[0]
                self.server.oauth_decision = q.get('decision', ['approve'])[0]
                return self.reply({'ok': True})
            if self.path == '/_qa/mcp-violations': return self.reply(self.server.mcp_violations)
            if self.path == '/_qa/mcp-posts': return self.reply({'count': self.server.mcp_posts})
            if self.path == '/_qa/mcp-restart': self.server.mcp_sessions.clear(); return self.reply({'ok': True})
            if self.path == '/_qa/mcp-revoke': self.server.revoked.add('mcp-fixture'); return self.reply({'ok': True})
            if self.path in ('/_qa/mcp-down', '/_qa/mcp-up'): self.server.mcp_down = self.path.endswith('-down'); return self.reply({'ok': True})
            if self.path == '/_qa/mcp-calls': return self.reply(self.server.mcp_calls)
            if self.path.startswith('/oauth/mcp/authorize?'):
                q = parse_qs(urlparse(self.path).query)
                assert q['code_challenge_method'] == ['S256']
                assert q['redirect_uri'] == [self.server.oauth_redirect]
                assert q['resource'] == [base + '/mcp']
                self.server.oauth_challenge = q['code_challenge'][0]
                result = {'redirect': self.server.oauth_redirect, 'state': q['state'][0], 'iss': base}
                result.update({'error': 'access_denied'} if self.server.oauth_decision == 'deny' else {'code': 'fixture-code'})
                return self.reply(result)
            if self.path == '/.well-known/t3/environment': return self.reply({'environmentId': 'fixture-' + self.server.machine, 'label': 'Fixture ' + self.server.machine, 'orchestrationProtocolVersion': self.server.protocol_version})
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
            token, scope = {'full-access': ('mcp-fixture', 'orchestration:read orchestration:operate settings:write source-control:write'), 'approval-required': ('mcp-supervised', 'orchestration:read orchestration:operate'), 'read-only': ('mcp-readonly', 'orchestration:read')}[self.server.oauth_access]
            return self.reply({'access_token': token, 'expires_in': 2592000, 'token_type': 'Bearer', 'scope': scope})
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
        self.server.mcp_posts += 1
        auth = self.headers.get('Authorization', '').removeprefix('Bearer ')
        if auth not in ('mcp-fixture', 'mcp-readonly', 'mcp-supervised') or auth in self.server.revoked:
            return self.reply({'error': 'invalid_mcp_credential', 'message': 'A valid T3 Code MCP credential is required.'}, 401, {'WWW-Authenticate': f'Bearer resource_metadata="http://127.0.0.1:{self.server.server_port}/.well-known/oauth-protected-resource/mcp", error="invalid_token"'})
        if self.server.mcp_down: return self.reply({'message': 'Fixture MCP offline'}, 503)
        if self.headers.get('Content-Type') != 'application/json': return self.reply_empty(415)
        if any(t not in self.headers.get('Accept', '') for t in ('application/json', 'text/event-stream')): return self.reply_empty(406)
        method = request['method']
        session = self.headers.get('Mcp-Session-Id')
        def rejected(message): return self.reply({'jsonrpc': '2.0', 'id': request.get('id'), 'error': {'code': -32600, 'message': message}}, 400)
        if method == 'initialize':
            if session: return rejected('initialize must not include an MCP-Session-Id header')
            session = str(uuid.uuid4()); self.server.mcp_sessions.add(session)
            self.server.mcp_calls.append({'name': 'initialize'})
            return self.reply({'jsonrpc': '2.0', 'id': request['id'], 'result': {'protocolVersion': '2025-06-18', 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'fixture', 'version': '1'}}}, headers={'Mcp-Session-Id': session, 'MCP-Protocol-Version': '2025-06-18'})
        if not session: return rejected('MCP-Session-Id header is required; send initialize to start a session')
        if session not in self.server.mcp_sessions: return self.reply_empty(404)
        if self.headers.get('MCP-Protocol-Version', '2025-06-18') != '2025-06-18': return rejected('Unsupported protocol version')
        if method == 'notifications/initialized': return self.reply_empty(202)
        assert method == 'tools/call'
        name, args = request['params']['name'], request['params']['arguments']
        self.server.mcp_calls.append({'name': name, 'arguments': args})
        if auth == 'mcp-readonly' and (name in ('t3_thread_send', 't3_thread_launch', 't3_thread_interrupt') or 'answers' in args or 'modelSelection' in args):
            return self.reply({'jsonrpc': '2.0', 'id': request['id'], 'result': {'isError': True, 'content': [{'type': 'text', 'text': json.dumps({'_tag': 'OrchestratorMcpFailure', 'code': 'capability_denied', 'message': 'This tool changes the environment, and this MCP client was approved for read-only access.'})}]}})
        if auth == 'mcp-supervised' and name in ('t3_thread_send', 't3_thread_interrupt', 't3_thread_launch'):
            return self.reply({'jsonrpc': '2.0', 'id': request['id'], 'result': {'isError': True, 'content': [{'type': 'text', 'text': json.dumps({'_tag': 'OrchestratorMcpFailure', 'code': 'runtime_mode_escalation_denied', 'message': 'Child runtime mode full-access is broader than parent mode approval-required.'})}]}})
        allowed = {
            't3_thread_list': 'environmentId projectId allProjects statuses titleContains settled snoozed includeSubagents cursor limit',
            't3_project_list': 'environmentId cursor limit',
            't3_thread_read': 'environmentId threadId itemId textOffset view afterPosition limit runLimit maxCharsPerItem',
            't3_pending_request': 'action environmentId threadId requestId answers',
            'orchestrator_capabilities': 'environmentId search',
            't3_thread_configure': 'environmentId threadId modelSelection',
            't3_thread_send': 'environmentId threadId message mode clientRequestId',
            't3_thread_launch': 'environmentId projectId scratch title modelSelection runtimeMode interactionMode workspaceStrategy message attachments clientRequestId delegatedFrom',
            't3_thread_interrupt': 'environmentId threadId runId reason stop clientRequestId',
        }
        extra = sorted(set(args) - set(allowed[name].split()))
        if extra:
            if name == 't3_thread_read': return self.reply({'jsonrpc': '2.0', 'id': request['id'], 'error': {'code': -32602, 'message': 'Unexpected key ' + ', '.join(extra)}})
            self.server.mcp_violations.append({'tool': name, 'extra': extra})
        if name in ('t3_thread_list', 't3_project_list'): assert 1 <= args.get('limit', 100) <= 100
        if 'runLimit' in args: assert args['runLimit'] >= 1
        if name == 't3_thread_launch': assert args['title'] == args['title'].strip() and args['title']
        if name == 't3_thread_send': assert args['mode'] in ('auto', 'queue', 'steer', 'restart')
        if name == 't3_pending_request': assert args['action'] in ('list', 'read', 'respond')
        ident = args.get('threadId', 'mcp-question')
        now = '2026-11-01T00:00:00Z'
        row = {'threadId': ident, 'projectId': 'mcp-project', 'title': 'MCP fixture', 'status': 'completed' if self.server.mcp_answered else 'waiting', 'latestRunId': 'mcp-run', 'providerInstanceId': 'fixture-provider', 'model': 'fixture-model', 'runtimeMode': 'full-access', 'interactionMode': 'default', 'updatedAt': now}
        def visibility_row(value):
            result = {**row, **{k: v for k, v in value.items() if k not in ('activities', 'messages', 'latestTurn', 'id')}}
            result['threadId'] = value['id']
            result['status'] = 'waiting' if value['hasPendingUserInput'] and value['id'] not in self.server.mcp_answered_ids else 'completed'
            return result
        if ident.startswith('vis-') and ident in self.server.threads: row = visibility_row(self.server.threads[ident])
        questions = [{'id': 'format', 'header': 'Format', 'question': 'Which format?', 'options': [{'label': 'JSON', 'description': 'Structured', 'value': 'json'}], 'allowCustomAnswer': True}]
        if name == 't3_thread_list':
            assert args['allProjects'] is True
            cursor = args.get('cursor', 0)
            rows = [{**row, 'threadId': 'mcp-question'}, {**row, 'threadId': 'mcp-approval', 'status': 'waiting'}]
            if self.server.visibility:
                rows += [visibility_row(v) for key, v in self.server.threads.items() if key.startswith('vis-')]
            rows = [r for r in rows if not r.get('lineage', {}).get('sideChat')]
            if not args.get('includeSubagents', True): rows = [r for r in rows if not (r.get('lineage', {}).get('relationshipToParent') == 'subagent' or r.get('lineage', {}).get('sideChat'))]
            if args.get('statuses'): rows = [r for r in rows if r['status'] in args['statuses']]
            result = {'threads': rows[cursor:cursor+1], 'nextCursor': cursor+1 if cursor+1 < len(rows) else None, 'total': len(rows)}
        elif name == 't3_project_list': result = {'projects': [{'id': 'mcp-project', 'title': 'MCP project'}], 'nextCursor': None}
        elif name == 't3_thread_read':
            row['worktreePath'] = '/fixture/worktree'
            row['branch'] = 'fixture-branch'
            if self.server.mcp_detail_error and ident == 'mcp-question':
                return self.reply({'jsonrpc': '2.0', 'id': request['id'], 'result': {'isError': True, 'content': [{'type': 'text', 'text': 'Thread detail unavailable'}]}})
            history = list(self.server.mcp_items)
            if self.server.mcp_sent:
                history.append({'position': 3, 'itemId': 'message-2', 'type': 'assistant_message', 'text': 'Sent', 'status': 'completed', 'updatedAt': now})
            if 'itemId' in args:
                items = [item for item in history if item['itemId'] == args['itemId']]
                more = False
            elif args.get('view') == 'activity':
                remaining = [item for item in history if item['position'] > args.get('afterPosition', 0)]
                items, more = remaining[:1], len(remaining) > 1
            else: items, more = [], False
            result = {'thread': row, 'recentRuns': [], 'items': items, 'hasMore': more, 'nextPosition': items[-1]['position'] if items else None}
            if self.server.mcp_history_mode == 'long':
                position = args.get('afterPosition', 0) + 1
                result.update(items=[{'position': position, 'itemId': f'long-{position}', 'type': 'assistant_message', 'text': str(position), 'status': 'completed'}], hasMore=True, nextPosition=position)
            elif self.server.mcp_history_mode == 'null': result.update(items=[], hasMore=True, nextPosition=None)
        elif name == 't3_pending_request':
            action = args['action']
            if action == 'list': result = {'requestIds': ['mcp-request'] if (ident == 'mcp-question' and not self.server.mcp_answered or ident.startswith('vis-') and row['status'] == 'waiting') else []}
            elif action == 'read': result = {'requestId': 'mcp-request', 'questions': questions}
            else:
                assert args['requestId'] == 'mcp-request' and args['answers'] == {'format': 'json'}
                self.server.mcp_answered_ids.add(ident)
                if ident == 'mcp-question': self.server.mcp_answered = True
                result = {'sequence': 1}
        elif name == 'orchestrator_capabilities': result = {'providers': [{'providerInstanceId': 'fixture-provider', 'driverKind': 'codex', 'displayName': 'Fixture', 'models': [{'id': 'fixture-model', 'label': 'Model'}], 'constraints': [], 'omittedModels': 2}]}
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
        reply = {'jsonrpc': '2.0', 'id': request['id'], 'result': {'isError': False, 'structuredContent': result, 'content': [{'type': 'text', 'text': json.dumps(result)}]}}
        # Exercise both Streamable HTTP response formats and both tool-result encodings.
        if name == 't3_thread_read':
            payload = ('data: ' + json.dumps({'jsonrpc': '2.0', 'method': 'notifications/message', 'params': {'level': 'info', 'data': 'fixture'}}) + '\n\ndata: ' + json.dumps(reply) + '\n\n').encode()
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
        if command['type'] in ('thread.turn.start', 'message.dispatch'):
            target['messages'][0]['text'] = 'Sent via legacy'
            return
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
                    if command['type'] not in ('runtime-request.respond', 'message.dispatch', 'thread.model-selection.set'): raise ValueError('Unexpected command')
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
