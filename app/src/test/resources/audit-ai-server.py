import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Handler(BaseHTTPRequestHandler):
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  system=body['messages'][0]['content']
  user=body['messages'][1]['content']
  code=200
  if user=='ERROR_429': code=429; data={'error':{'message':'rate limit'}}
  elif user=='MALFORMED': data={'unexpected':'data'}
  else:
   result='Validated response'
   if 'detected_language' in system: result=json.dumps({'translation':'Validated translation','detected_language':'en'})
   if 'professional dictionary' in system: result=json.dumps({'entries':[{'word':'hello','part_of_speech':'interjection','definitions':[{'text':'greeting'}]}]})
   elif 'corrected_text' in system: result=json.dumps({'corrected_text':'hello','corrections':[]})
   data={'choices':[{'message':{'role':'assistant','content':result},'finish_reason':'length' if user=='TRUNCATED' else 'stop'}]}
  raw=json.dumps(data).encode()
  self.send_response(code); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(raw)
 def log_message(self,*args): pass
if __name__ == '__main__':
 ThreadingHTTPServer(('127.0.0.1',18765),Handler).serve_forever()

