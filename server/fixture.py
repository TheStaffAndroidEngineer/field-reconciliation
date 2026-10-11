#!/usr/bin/env python3
"""Synthetic Common Goods Field service; blob and report are separate durable transactions."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse, hashlib, json, re, socket, sqlite3, threading, time
MAX_PHOTO = 262144
DEFAULT = dict(uploadHttp=200, reportHttp=200, lookupHttp=200,
               dropUploadOnce=False, dropReportOnce=False, delayMs=0, schema=1)
def valid_note(value):
    if not isinstance(value,str):return False
    try:return len(value.encode("utf-16-le"))//2<=160 and not any(ord(c)<32 or 127<=ord(c)<=159 for c in value)
    except UnicodeEncodeError:return False
def unique_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError("duplicate JSON key")
        result[key]=value
    return result
class State:
    def __init__(self, database, log):
        self.lock=threading.RLock(); self.db=sqlite3.connect(database,check_same_thread=False)
        self.db.execute('PRAGMA foreign_keys=ON'); self.db.execute('PRAGMA journal_mode=WAL'); self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript('''CREATE TABLE IF NOT EXISTS photos(digest TEXT PRIMARY KEY,bytes BLOB NOT NULL);
        CREATE TABLE IF NOT EXISTS reports(id TEXT PRIMARY KEY,payload TEXT NOT NULL,revision INTEGER NOT NULL,photo TEXT NOT NULL REFERENCES photos(digest));
        CREATE TABLE IF NOT EXISTS visit(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,condition TEXT NOT NULL,note TEXT NOT NULL);
        INSERT OR IGNORE INTO visit VALUES('CG-204',7,'Good','Packaging intact');''')
        self.db.commit(); self.control=DEFAULT.copy(); self.events=[]; self.log=log; self.sequence=0
    def event(self, **row):
        with self.lock:
            self.sequence+=1; row=dict(sequence=self.sequence,**row); self.events.append(row)
            if self.log:
                with self.log.open('a') as stream:stream.write(json.dumps(row,separators=(',',':'))+'\n')
    def assessment(self):
        row=self.db.execute('SELECT id,revision,condition,note FROM visit').fetchone()
        return dict(visitId=row[0],revision=row[1],condition=row[2],note=row[3])
    def receipt(self,row):return dict(payload=json.loads(row[0]),revision=row[1])
    def view(self):
        with self.lock:return dict(control=self.control.copy(),events=list(self.events),current=self.assessment(),
            photoCount=self.db.execute('SELECT COUNT(*) FROM photos').fetchone()[0],
            reportCount=self.db.execute('SELECT COUNT(*) FROM reports').fetchone()[0])
class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def setup(self):super().setup();self.connection.settimeout(8)
    def send(self,status,payload):
        raw=json.dumps(payload,separators=(',',':')).encode()
        try:
            self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Content-Length',str(len(raw)));
            if self.close_connection:self.send_header('Connection','close')
            self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw)
        except (BrokenPipeError,ConnectionResetError,TimeoutError):pass
    def body(self,limit):
        values=self.headers.get_all('Content-Length',[])
        if self.headers.get('Transfer-Encoding') or len(values)!=1 or not re.fullmatch('[0-9]{1,7}',values[0]):raise ValueError()
        size=int(values[0])
        if not 0<size<=limit:raise ValueError()
        data=self.rfile.read(size)
        if len(data)!=size:raise ValueError()
        return data
    def json_body(self):
        value=json.loads(self.body(4096).decode('utf-8'),object_pairs_hook=unique_object)
        if not isinstance(value,dict):raise ValueError()
        return value
    def control(self,key):
        with self.server.state.lock:return self.server.state.control[key]
    def delayed(self):time.sleep(self.control('delayMs')/1000)
    def drop(self,key,route,identity):
        s=self.server.state
        with s.lock:
            if not s.control[key]:return False
            s.control[key]=False
        s.event(route=route,identity=identity,kind='reply_lost')
        self.close_connection=True
        try:self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:pass
        self.connection.close();return True
    def do_GET(self):
        s=self.server.state
        if self.path=='/admin/state':return self.send(200,s.view())
        report=re.fullmatch('/v1/reports/([a-f0-9]{32})',self.path)
        photo=re.fullmatch('/v1/photos/([a-f0-9]{64})',self.path)
        if not report and not photo:return self.send(404,dict(error='not_found'))
        self.delayed()
        if self.control('lookupHttp')!=200:
            s.event(route='report_lookup' if report else 'photo_lookup',identity=report[1] if report else photo[1],kind='unavailable')
            return self.send(503,dict(error='unavailable'))
        with s.lock:
            if report:
                identity=report[1];row=s.db.execute('SELECT payload,revision FROM reports WHERE id=?',(identity,)).fetchone()
                s.event(route='report_lookup',identity=identity,kind='found' if row else 'missing')
                return self.send(200,dict(schema=self.control('schema'),receipt=s.receipt(row))) if row else self.send(404,dict(error='unknown_operation'))
            identity=photo[1];row=s.db.execute('SELECT length(bytes) FROM photos WHERE digest=?',(identity,)).fetchone()
            s.event(route='photo_lookup',identity=identity,kind='found' if row else 'missing')
            return self.send(200,dict(schema=self.control('schema'),digest=identity,size=row[0])) if row else self.send(404,dict(error='missing_photo'))
    def do_PUT(self):
        s=self.server.state;match=re.fullmatch('/v1/photos/([a-f0-9]{64})',self.path)
        if not match:return self.send(404,dict(error='not_found'))
        try:data=self.body(MAX_PHOTO)
        except (ValueError,TimeoutError):
            self.close_connection=True;return self.send(400,dict(error='invalid_body'))
        identity=match[1]
        if hashlib.sha256(data).hexdigest()!=identity:return self.send(422,dict(error='digest_mismatch'))
        self.delayed()
        if self.control('uploadHttp')!=200:
            s.event(route='photo',identity=identity,kind='unavailable')
            return self.send(503,dict(error='unavailable'))
        with s.lock,s.db:
            old=s.db.execute('SELECT bytes FROM photos WHERE digest=?',(identity,)).fetchone()
            if old and old[0]!=data:return self.send(409,dict(error='digest_reused'))
            if not old:s.db.execute('INSERT INTO photos VALUES(?,?)',(identity,data))
        s.event(route='photo',identity=identity,kind='replayed' if old else 'stored',bytes=len(data))
        if self.drop('dropUploadOnce','photo',identity):return
        self.send(200,dict(schema=self.control('schema'),digest=identity,size=len(data)))
    def do_POST(self):
        s=self.server.state
        try:body=self.json_body()
        except (ValueError,UnicodeDecodeError,TimeoutError):
            self.close_connection=True;return self.send(400,dict(error='invalid_body'))
        if self.path.startswith('/admin/'):
            with s.lock:
                if self.path=='/admin/reset':
                    if body:return self.send(400,dict(error='unexpected_fields'))
                    with s.db:
                        s.db.execute('DELETE FROM reports');s.db.execute('DELETE FROM photos');s.db.execute("UPDATE visit SET revision=7,condition='Good',note='Packaging intact'")
                    s.control=DEFAULT.copy();s.events=[];s.sequence=0;return self.send(200,s.view())
                if self.path=='/admin/advance':
                    if body:return self.send(400,dict(error='unexpected_fields'))
                    with s.db:s.db.execute("UPDATE visit SET revision=revision+1,condition='Good',note='Rechecked at the depot' WHERE revision<999999")
                    s.event(route='admin',kind='advanced');return self.send(200,s.assessment())
                if self.path=='/admin/prune':
                    if body:return self.send(400,dict(error='unexpected_fields'))
                    with s.db:s.db.execute('DELETE FROM photos WHERE digest NOT IN (SELECT photo FROM reports)')
                    return self.send(200,s.view())
                if self.path=='/admin/config':
                    if not set(body)<=set(DEFAULT):return self.send(400,dict(error='unexpected_fields'))
                    for key,value in body.items():
                        valid=(type(value) is bool) if key.startswith('drop') else (type(value) is int and (value in [200,503] if key.endswith('Http') else value in [1,2] if key=='schema' else 0<=value<=3000))
                        if not valid:return self.send(400,dict(error='invalid_control'))
                    s.control.update(body);return self.send(200,s.control)
            return self.send(404,dict(error='not_found'))
        if self.path!='/v1/reports':return self.send(404,dict(error='not_found'))
        keys={'operationId','visitId','baseRevision','condition','note','photoDigest'}
        if set(body)!=keys or body.get('visitId')!='CG-204' or type(body.get('baseRevision')) is not int or not 1<=body['baseRevision']<=1000000 or body.get('condition') not in ['Good','Damaged'] or not valid_note(body.get('note')) or not isinstance(body.get('operationId'),str) or not re.fullmatch('[a-f0-9]{32}',body['operationId']) or not isinstance(body.get('photoDigest'),str) or not re.fullmatch('[a-f0-9]{64}',body['photoDigest']):return self.send(400,dict(error='invalid_report'))
        identity=body['operationId']
        if self.headers.get_all('Idempotency-Key',[])!=[identity]:return self.send(400,dict(error='invalid_identity'))
        self.delayed()
        if self.control('reportHttp')!=200:
            s.event(route='report',identity=identity,kind='unavailable')
            return self.send(503,dict(error='unavailable'))
        encoded=json.dumps(body,sort_keys=True,separators=(',',':'))
        with s.lock,s.db:
            old=s.db.execute('SELECT payload,revision FROM reports WHERE id=?',(identity,)).fetchone()
            if old:
                if old[0]!=encoded:return self.send(409,dict(error='identity_reused'))
                receipt=s.receipt(old);kind='replayed'
            else:
                current=s.assessment()
                if body['baseRevision']!=current['revision']:
                    s.event(route='report',identity=identity,kind='conflict',revision=current['revision'])
                    return self.send(409,dict(schema=self.control('schema'),error='revision_conflict',current=current))
                if not s.db.execute('SELECT 1 FROM photos WHERE digest=?',(body['photoDigest'],)).fetchone():return self.send(409,dict(schema=self.control('schema'),error='missing_photo'))
                if current['revision']>=1000000:return self.send(503,dict(error='revision_limit'))
                revision=current['revision']+1
                s.db.execute('UPDATE visit SET revision=?,condition=?,note=?',(revision,body['condition'],body['note']))
                s.db.execute('INSERT INTO reports VALUES(?,?,?,?)',(identity,encoded,revision,body['photoDigest']))
                receipt=dict(payload=body,revision=revision);kind='committed'
        s.event(route='report',identity=identity,kind=kind,revision=receipt['revision'])
        if self.drop('dropReportOnce','report',identity):return
        self.send(200,dict(schema=self.control('schema'),receipt=receipt))
def main():
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8890);p.add_argument('--database',type=Path,required=True);p.add_argument('--log',type=Path);a=p.parse_args()
    a.database.parent.mkdir(parents=True,exist_ok=True)
    if a.log:a.log.parent.mkdir(parents=True,exist_ok=True)
    state=State(a.database,a.log);server=ThreadingHTTPServer(('127.0.0.1',a.port),Handler);server.state=state
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close();state.db.close()
if __name__=='__main__':main()
