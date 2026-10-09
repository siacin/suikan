"""Local fixture only. Never forwards requests to a real model or records keys."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time
import socket
import socketserver
import threading
import re

def fast_result(context,prompt,invalid=False):
    if 'transcript' in context:
        ids=re.findall(r'^\[(s[^\]\s]+)\] ',context['transcript'],re.M)
    else:
        ids=list(dict.fromkeys(sid for part in context['parts'] for key in ('key_points','chapters','steps') for row in part.get(key,[]) for sid in row.get('segment_ids',[])))
    concise='300至600' in prompt;count=2 if concise else 5
    keys=[{'title':f'要点{i+1}','detail':'主要结论。' if concise else '主要结论、理由与适用条件。','segment_ids':['forged' if invalid else ids[-1] if i==count-1 else ids[i%len(ids)],'forged']} for i in range(count)]
    return {'overview':'完整测试总结','key_points':keys,'chapters':[],'steps':[],'caveats':[]}

class Handler(BaseHTTPRequestHandler):
    counts = {}
    def log_message(self, *args):
        pass

    def do_GET(self):
        output = json.dumps(self.counts).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(output)))
        self.end_headers()
        self.wfile.write(output)

    def do_POST(self):
        assert not self.headers.get('Cookie'), 'Bilibili cookies must never reach the model'
        length = int(self.headers.get('Content-Length', '0'))
        body = json.loads(self.rfile.read(length))
        model = body.get('model')
        self.counts[model] = self.counts.get(model, 0) + 1
        if model == 'context-error':
            output=json.dumps({'error':{'message':'maximum context length exceeded','code':'context_length_exceeded'}}).encode()
            self.send_response(400);self.send_header('Content-Length',str(len(output)));self.end_headers();self.wfile.write(output);return
        if model in ('fast-stream','fast-broken'):
            context=json.loads(body.get('input') or body['messages'][-1]['content']);prompt=body.get('instructions') or body['messages'][0]['content'];payload=fast_result(context,prompt)
            payload['overview']='正在生成的测试概述，完整结束后才能保存。'
            text=json.dumps(payload,ensure_ascii=False);at=text.index('", "key_points"')
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
            def event(delta,finish=None):
                self.wfile.write(('data: '+json.dumps({'choices':[{'delta':delta,'finish_reason':finish}]},ensure_ascii=False)+'\n\n').encode());self.wfile.flush()
            try:
                event({'reasoning_content':'private-thinking'})
                event({'content':text[:at]});time.sleep(8)
                if model=='fast-broken':self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                event({'content':text[at:]},'stop');self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
            return
        if model == 'checkpoint-fail' and self.counts[model] == 2:
            output=json.dumps({'error':{'message':'temporary fixture error'}}).encode()
            self.send_response(503); self.send_header('Content-Length',str(len(output)));self.end_headers();self.wfile.write(output);return
        if model in ('empty-sse-once','empty-json-once','empty-always') and (model == 'empty-always' or self.counts[model] % 2 == 1):
            if model == 'empty-sse-once': output=b'data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'; mime='text/event-stream'
            else: output=b'{"choices":[{"message":{"content":"","refusal":null},"finish_reason":"stop"}]}'; mime='application/json'
            self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(output)));self.end_headers();self.wfile.write(output);return
        if model in ('chunked-eof-once','chunked-eof-always') and (model == 'chunked-eof-always' or self.counts[model] % 2 == 1):
            self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.send_header('Transfer-Encoding','chunked'); self.end_headers()
            data = b'data: {"choices":[{"delta":{"content":"{\\"ok\\":true}"},"finish_reason":null}]}\n\n'
            self.wfile.write(format(len(data),'x').encode()+b'\r\n'+data+b'\r\n'); self.wfile.flush()
            self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
        if model == 'auth-error':
            output = json.dumps({'error': {'message': 'Unauthorized'}}).encode()
            self.send_response(401); self.send_header('Content-Length', str(len(output))); self.end_headers(); self.wfile.write(output); return
        if model == 'split-reset':
            context = json.loads(body.get('input') or body['messages'][-1]['content'])
            if len(context.get('segments', context.get('points', []))) > 2:
                self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
        if model == 'telemetry-sse':
            time.sleep(2)
            self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
            chunks = [b': keepalive\n\n', b'data: {"choices":[{"delta":{"reasoning_content":"private-thinking"},"finish_reason":null}]}\n\n', b'data: {"choices":[{"delta":{"content":"{\\"ok\\":"},"finish_reason":null}]}\n\n', b'data: {"choices":[{"delta":{"content":"true}"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n']
            try:
                for chunk in chunks:
                    self.wfile.write(chunk); self.wfile.flush(); time.sleep(2)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass
            return
        if model == 'reset-once' and self.counts[model] == 1:
            self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
        if model == 'echo-fallback' and 'max_tokens' in body:
            output = json.dumps({'error': {'message': 'max_tokens is unsupported; use max_completion_tokens'}}).encode()
            self.send_response(400)
            self.send_header('Content-Length', str(len(output)))
            self.end_headers()
            self.wfile.write(output)
            return
        if model in ('echo', 'echo-fallback', 'deepseek-v4.1-flash', 'glm-5.3-flash'):
            received = {k: body[k] for k in ('max_tokens', 'max_completion_tokens', 'max_output_tokens', 'thinking', 'reasoning', 'reasoning_effort', 'stream') if k in body}
            content = json.dumps({'received': received})
            result = {'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': content}]}], 'status': 'completed'} if self.path.endswith('/responses') else {'choices': [{'message': {'content': content}, 'finish_reason': 'stop'}]}
        elif model in ('truncated', 'truncated-sse'):
            result = {'choices': [{'message': {'content': '{"ok":true}', 'reasoning_content': 'fixture-private-reasoning'}, 'finish_reason': 'length'}], 'usage': {'completion_tokens': 6000, 'completion_tokens_details': {'reasoning_tokens': 5900}}}
        elif model in ('truncated-responses', 'truncated-responses-sse'):
            result = {'status': 'incomplete', 'incomplete_details': {'reason': 'max_output_tokens'}, 'output': [], 'usage': {'output_tokens': 8000, 'output_tokens_details': {'reasoning_tokens': 7900}}}
        elif model in ('responses-test', 'responses-sse', 'sse-test', 'keepalive-sse', 'broken-sse', 'slow-sse', 'delayed-model', 'reset-once', 'eof-once', 'chunked-eof-once', 'empty-sse-once', 'empty-json-once'):
            result = {'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': '{"ok":true}'}]}], 'status': 'completed'} if model.startswith('responses') else {'choices': [{'message': {'content': '{"ok":true}'}, 'finish_reason': 'stop'}]}
        else:
            context = json.loads(body.get('input') or body['messages'][-1]['content'])
            if 'transcript' in context or 'parts' in context:
                prompt=body.get('instructions') or body['messages'][0]['content'];payload=fast_result(context,prompt,model=='invalid-refs')
            elif 'segments' in context:
                segments = context['segments']
                if model == 'invalid-refs':
                    payload = {'points': [{'title': '伪造', 'detail': '无来源', 'evidence_segment_ids': ['forged']}]}
                else:
                    payload = {'points': [{'title': '本段要点', 'detail': '整理本段完整内容与条件。', 'evidence_segment_ids': [segments[0]['segment_id'], 'forged']}, {'title': '本段结尾', 'detail': '保留结尾知识点。', 'evidence_segment_ids': [segments[-1]['segment_id']]}]}
                    if model == 'split-reset':
                        payload = {'points': [{'title': '知识点', 'detail': s['text'], 'evidence_segment_ids': [s['segment_id']]} for s in segments]}
            elif 'section_overviews' in context:
                payload = {'overview': '完整测试总结'}
            elif 'candidate_points' in context:
                rows=context['candidate_points'];payload={'point_ids':[rows[0]['id'],rows[-1]['id']]}
            else:
                points = context['points']
                payload = {'overview': '完整测试总结', 'key_points': [{'title': '全视频要点', 'detail': '包括前中后段内容。', 'point_ids': [p['id'] for p in points] + ['forged']}], 'chapters': [{'title': '第一章', 'summary': '最早来源。', 'point_ids': [points[0]['id']], 'start': 999999}], 'steps': [], 'caveats': []}
                if model == 'depth-fixture':
                    prompt=body['messages'][0]['content'];concise='300至600' in prompt;standard='800至1500' in prompt
                    if concise or standard:
                        payload['key_points']=[{'title':f'要点{i+1}','detail':'主要结论。' if concise else '主要结论，以及相应理由、例子和适用条件。','point_ids':[points[min(i,len(points)-1)]['id']]} for i in range(2 if concise else 5)]
                        payload['chapters']=[]
            result = {'choices': [{'message': {'content': json.dumps(payload, ensure_ascii=False)}, 'finish_reason': 'stop'}]}
        if model == 'truncated-sse':
            text = 'data: ' + json.dumps({'choices': [{'delta': {'content': '{"ok":true}'}, 'finish_reason': 'length'}]}) + '\n\ndata: ' + json.dumps({'choices': [], 'usage': result['usage']}) + '\n\ndata: [DONE]\n\n'
            output, mime = text.encode(), 'text/event-stream'
        elif model == 'truncated-responses-sse':
            text = 'event: response.incomplete\ndata: ' + json.dumps({'type': 'response.incomplete', 'response': result}) + '\n\n'
            output, mime = text.encode(), 'text/event-stream'
        elif model == 'sse-test':
            text = 'data: ' + json.dumps({'choices': [{'delta': {'content': '{"ok":'}, 'finish_reason': None}]}) + '\n\ndata: ' + json.dumps({'choices': [{'delta': {'content': 'true}'}, 'finish_reason': 'stop'}]}) + '\n\ndata: [DONE]\n\n'
            output, mime = text.encode(), 'text/event-stream'
        elif model == 'responses-sse':
            text = 'event: response.output_text.delta\ndata: ' + json.dumps({'type': 'response.output_text.delta', 'delta': '{"ok":true}'}) + '\n\nevent: response.completed\ndata: ' + json.dumps({'type': 'response.completed', 'response': result}) + '\n\n'
            output, mime = text.encode(), 'text/event-stream'
        elif model in ('keepalive-sse', 'broken-sse', 'slow-sse', 'eof-once'):
            if model == 'slow-sse':
                self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
                try:
                    for i in range(250):
                        self.wfile.write(b': keepalive\n\n'); self.wfile.flush(); time.sleep(1)
                    self.wfile.write(b'data: {"choices":[{"delta":{"content":"{\\"ok\\":true}"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass
                return
            broken = model == 'broken-sse' or (model == 'eof-once' and self.counts[model] == 1)
            text = ': keepalive\n\n' + 'data: ' + json.dumps({'choices': [{'delta': {'content': '{"ok":true}'}, 'finish_reason': None if broken else 'stop'}]}) + '\n\n'
            if not broken: text += 'data: [DONE]\n\n'
            output, mime = text.encode(), 'text/event-stream'
        else:
            output, mime = json.dumps(result, ensure_ascii=False).encode(), 'application/json'
        if model == 'delayed-model': time.sleep(190)
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(output)))
        self.end_headers()
        try: self.wfile.write(output)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass

if __name__ == '__main__':
    class ClosingTLS(socketserver.BaseRequestHandler):
        def handle(self):
            Handler.counts['tls-close']=Handler.counts.get('tls-close',0)+1
            self.request.recv(4096)
            self.request.shutdown(socket.SHUT_RDWR)
            self.request.close()
    class TLSServer(socketserver.ThreadingTCPServer):
        allow_reuse_address=True
        daemon_threads=True
    tls=TLSServer(('127.0.0.1',18766),ClosingTLS)
    threading.Thread(target=tls.serve_forever,daemon=True).start()
    print('Local mock model ready on 127.0.0.1:18765', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 18765), Handler).serve_forever()
