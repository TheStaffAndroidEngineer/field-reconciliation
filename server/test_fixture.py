#!/usr/bin/env python3
"""Independent real HTTP and durable-server contracts; isolated owned fixture only."""
import argparse, hashlib, http.client, json, pathlib, signal, socket, sqlite3, subprocess, sys, threading, time, unittest
from concurrent.futures import ThreadPoolExecutor
ROOT = pathlib.Path(__file__).resolve().parent
PORT = 8891
STAGES = []
PHOTO = bytes([1, 3, 5, 7])
DIGEST = hashlib.sha256(PHOTO).hexdigest()
OWNER = None

def request(method, path, payload=None, raw=None, headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', PORT, timeout=8)
    body = json.dumps(payload, ensure_ascii=True).encode() if payload is not None else raw
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse(); data = response.read()
        return response.status, dict(response.getheaders()), json.loads(data)
    finally: connection.close()

def state(): return request('GET', '/admin/state')[2]
def config(**fields):
    status, _, _ = request('POST', '/admin/config', fields)
    if status != 200: raise AssertionError(status)
def payload(op='a'*32, base=7, note='Crack beside the lens', digest=DIGEST):
    return dict(operationId=op, visitId='CG-204', baseRevision=base, condition='Damaged', note=note, photoDigest=digest)
def upload(data=PHOTO): return request('PUT', '/v1/photos/'+hashlib.sha256(data).hexdigest(), raw=data)
def commit(value=None):
    value=value or payload()
    return request('POST','/v1/reports',value,headers={'Idempotency-Key':value['operationId']})
def raw_request(raw):
    with socket.create_connection(('127.0.0.1',PORT), timeout=3) as connection:
        connection.settimeout(3); connection.sendall(raw); connection.shutdown(socket.SHUT_WR)
        response=b''
        while True:
            part=connection.recv(65536)
            if not part:break
            response+=part
    return response

class FixtureOwner:
    def __init__(self, output): self.output=output; self.process=None; self.sink=None; self.starts=[]
    def start(self):
        self.sink=(self.output/'fixture-process.txt').open('a')
        self.process=subprocess.Popen([sys.executable,str(ROOT/'fixture.py'),'--port',str(PORT),
            '--database',str(self.output/'server.sqlite'),'--log',str(self.output/'fixture-events.jsonl')],stdout=self.sink,stderr=self.sink)
        self.starts.append({'pid':self.process.pid})
        deadline=time.monotonic()+5
        while True:
            if self.process.poll() is not None:raise RuntimeError('Owned fixture exited before readiness')
            try:
                if request('GET','/admin/state')[0]==200:return
            except OSError:pass
            if time.monotonic()>deadline:raise RuntimeError('Fixture readiness timeout')
            time.sleep(.02)
    def stop(self):
        self.process.send_signal(signal.SIGINT); self.process.wait(timeout=5); self.sink.close()
        self.starts[-1]['exitCode']=self.process.returncode
    def restart(self): self.stop(); self.start()

class FixtureContractTest(unittest.TestCase):
    def setUp(self): self.assertEqual(200,request('POST','/admin/reset',{})[0])
    def tearDown(self):
        s=state()
        STAGES.append({'case':self._testMethodName,'events':s['events'],'photoCount':s['photoCount'],
                       'reportCount':s['reportCount'],'currentRevision':s['current']['revision'],'control':s['control']})
    def test_01_blob_receipt_is_not_report_commit_and_exact_upload_replays(self):
        for _ in range(2):
            status,headers,body=upload(); self.assertEqual(200,status)
            self.assertEqual({'schema':1,'digest':DIGEST,'size':len(PHOTO)},body)
            self.assertEqual('no-store',headers['Cache-Control'])
        s=state(); self.assertEqual((1,0,7),(s['photoCount'],s['reportCount'],s['current']['revision']))
        self.assertEqual(['stored','replayed'],[e['kind'] for e in s['events']])
        self.assertEqual(404,request('GET','/v1/reports/'+'a'*32)[0])
    def test_02_size_and_digest_boundaries_do_not_publish_invalid_bytes(self):
        self.assertEqual(422,request('PUT','/v1/photos/'+DIGEST,raw=b'wrong')[0])
        self.assertEqual(400,request('PUT','/v1/photos/'+DIGEST,raw=b'')[0])
        maximum=bytes(range(256))*1024
        self.assertEqual(200,upload(maximum)[0])
        self.assertEqual(400,upload(maximum+b'x')[0])
        self.assertEqual(1,state()['photoCount'])
    def test_03_report_commit_and_replay_are_atomic_before_later_cas(self):
        upload(); first=commit(); self.assertEqual(200,first[0]); self.assertEqual(8,first[2]['receipt']['revision'])
        request('POST','/admin/advance',{})
        replay=commit(); self.assertEqual(first[2],replay[2]); self.assertEqual(200,replay[0])
        self.assertEqual(first[2],request('GET','/v1/reports/'+'a'*32)[2])
        s=state(); self.assertEqual((1,9),(s['reportCount'],s['current']['revision']))
        self.assertEqual(['committed','replayed'],[e['kind'] for e in s['events'] if e['route']=='report'])
    def test_04_reused_committed_identity_with_different_payload_is_rejected(self):
        upload(); self.assertEqual(200,commit()[0])
        changed=payload(note='A different inspection')
        status,_,body=commit(changed); self.assertEqual(409,status); self.assertEqual('identity_reused',body['error'])
        self.assertEqual(payload(),request('GET','/v1/reports/'+'a'*32)[2]['receipt']['payload'])
        self.assertEqual(8,state()['current']['revision'])
    def test_05_conflict_keeps_uploaded_evidence_until_explicit_new_operation(self):
        upload(); request('POST','/admin/advance',{})
        status,_,body=commit(); self.assertEqual(409,status); self.assertEqual('revision_conflict',body['error'])
        self.assertEqual(8,body['current']['revision']); self.assertEqual(0,state()['reportCount'])
        self.assertEqual(200,request('GET','/v1/photos/'+DIGEST)[0])
        newer=payload(op='b'*32,base=8); self.assertEqual(200,commit(newer)[0])
        self.assertEqual(9,state()['current']['revision']); self.assertEqual(1,state()['photoCount'])
    def test_06_missing_blob_is_not_report_acceptance_and_pruning_respects_references(self):
        status,_,body=commit(); self.assertEqual(409,status); self.assertEqual('missing_photo',body['error'])
        self.assertEqual(0,state()['reportCount']); upload()
        request('POST','/admin/prune',{}); self.assertEqual(404,request('GET','/v1/photos/'+DIGEST)[0])
        upload(); self.assertEqual(200,commit()[0]); upload(b'unreferenced')
        self.assertEqual(2,state()['photoCount']); request('POST','/admin/prune',{})
        self.assertEqual(1,state()['photoCount']); self.assertEqual(200,request('GET','/v1/photos/'+DIGEST)[0])
    def test_07_lost_upload_reply_is_recoverable_without_replacing_identity(self):
        config(dropUploadOnce=True)
        with self.assertRaises((http.client.RemoteDisconnected,ConnectionResetError)): upload()
        status,_,body=request('GET','/v1/photos/'+DIGEST); self.assertEqual(200,status); self.assertEqual(len(PHOTO),body['size'])
        self.assertEqual(0,state()['reportCount']); self.assertEqual(200,commit()[0])
        self.assertEqual(1,len([e for e in state()['events'] if e['route']=='photo' and e['kind']=='stored']))
    def test_08_lost_report_reply_persists_the_exact_receipt(self):
        upload(); config(dropReportOnce=True)
        with self.assertRaises((http.client.RemoteDisconnected,ConnectionResetError)): commit()
        found=request('GET','/v1/reports/'+'a'*32)
        self.assertEqual(200,found[0]); self.assertEqual(payload(),found[2]['receipt']['payload'])
        self.assertEqual(found[2],commit()[2]); self.assertEqual(1,state()['reportCount'])
        self.assertEqual(8,state()['current']['revision'])
    def test_09_real_server_restart_preserves_blob_receipt_and_commit(self):
        upload(); accepted=commit()[2]; before=state()['events']
        OWNER.restart()
        self.assertEqual(accepted,request('GET','/v1/reports/'+'a'*32)[2])
        self.assertEqual(200,request('GET','/v1/photos/'+DIGEST)[0]); self.assertEqual(accepted,commit()[2])
        with sqlite3.connect(OWNER.output/'server.sqlite') as db:
            self.assertEqual(PHOTO,db.execute('SELECT bytes FROM photos WHERE digest=?',(DIGEST,)).fetchone()[0])
            self.assertEqual([],db.execute('PRAGMA foreign_key_check').fetchall())
            self.assertEqual((1,8),(db.execute('SELECT count(*) FROM reports').fetchone()[0],db.execute('SELECT revision FROM visit').fetchone()[0]))
        STAGES.append({'case':self._testMethodName+'_before_restart','events':before})
    def test_10_concurrent_duplicates_commit_once_and_competing_base_conflicts(self):
        upload()
        with ThreadPoolExecutor(max_workers=2) as pool: results=list(pool.map(lambda _:commit(),range(2)))
        self.assertEqual([200,200],[r[0] for r in results]); self.assertEqual(results[0][2],results[1][2])
        self.assertEqual(1,state()['reportCount']); self.assertEqual(8,state()['current']['revision'])
        STAGES.append({'case':self._testMethodName+'_duplicate_phase','events':state()['events']})
        request('POST','/admin/reset',{}); upload()
        with ThreadPoolExecutor(max_workers=2) as pool: results=list(pool.map(commit,[payload(),payload(op='b'*32)]))
        self.assertEqual([200,409],sorted(r[0] for r in results)); self.assertEqual(1,state()['reportCount'])
    def test_11_note_limit_is_shared_utf16_not_python_codepoint_count(self):
        upload()
        for note in ['x'*161,'😀'*81,'bad\nline','bad\x85line','\ud800','\udfff']:
            self.assertEqual(400,commit(payload(note=note))[0])
        exact='😀'*80; self.assertEqual(160,len(exact.encode('utf-16-le'))//2)
        self.assertEqual(200,commit(payload(note=exact))[0])
        self.assertEqual(exact,request('GET','/v1/reports/'+'a'*32)[2]['receipt']['payload']['note'])
    def test_12_controls_are_closed_typed_atomic_and_faults_do_not_commit(self):
        for values in [{'unknown':1},{'delayMs':True},{'delayMs':-1},{'delayMs':3001},{'schema':True},{'schema':3},
                       {'dropReportOnce':1},{'uploadHttp':201},{'reportHttp':False},{'lookupHttp':0},{'delayMs':20,'schema':False}]:
            before=state()['control']; self.assertEqual(400,request('POST','/admin/config',values)[0]); self.assertEqual(before,state()['control'])
        config(uploadHttp=503); self.assertEqual(503,upload()[0]); self.assertEqual(0,state()['photoCount'])
        config(uploadHttp=200,reportHttp=503); upload(); self.assertEqual(503,commit()[0]); self.assertEqual(0,state()['reportCount'])
        config(reportHttp=200,lookupHttp=503); self.assertEqual(503,request('GET','/v1/photos/'+DIGEST)[0])
        config(lookupHttp=200,schema=2); self.assertEqual(2,request('GET','/v1/photos/'+DIGEST)[2]['schema'])
    def test_13_report_fields_identity_and_framing_are_not_ambiguous(self):
        for key,value in [('baseRevision',True),('baseRevision',0),('baseRevision',1.0),('visitId','CG-205'),
                          ('condition','Lost'),('operationId','A'*32),('photoDigest','0'*63),('note',None)]:
            p=payload(); p[key]=value; self.assertEqual(400,commit(p)[0])
        p=payload(); p['unexpected']='value'; self.assertEqual(400,commit(p)[0])
        self.assertEqual(400,request('POST','/v1/reports',payload(),headers={'Idempotency-Key':'b'*32})[0])
        for raw in [b'[]',b'null',b'{',b'\xff',b'{"delayMs":0,"delayMs":1}']:
            self.assertEqual(400,request('POST','/admin/config',raw=raw)[0])
        body=json.dumps(payload()).encode()
        request_bytes=b'POST /v1/reports HTTP/1.1\r\nHost: localhost\r\nContent-Length: '+str(len(body)).encode()+b'\r\nIdempotency-Key: '+b'a'*32+b'\r\nIdempotency-Key: '+b'a'*32+b'\r\nConnection: close\r\n\r\n'+body
        self.assertTrue(raw_request(request_bytes).startswith(b'HTTP/1.1 400'))
        for header in [b'Content-Length: 2\r\nContent-Length: 2',b'Content-Length: 2\r\nTransfer-Encoding: chunked',b'Content-Length: -1']:
            raw=b'POST /admin/reset HTTP/1.1\r\nHost: localhost\r\n'+header+b'\r\n\r\n{}'
            self.assertTrue(raw_request(raw).startswith(b'HTTP/1.1 400'))
    def test_14_routes_are_exact_and_logs_do_not_contain_notes_or_photo_bytes(self):
        for path in ['/v1/photos/'+DIGEST+'?x=1','/v1/reports/'+'a'*32+'/', '/v1/photos/'+'A'*64]:
            self.assertEqual(404,request('GET',path)[0])
        upload(); commit(); events=state()['events']
        self.assertTrue(events)
        for event in events:self.assertLessEqual(set(event),{'sequence','route','identity','kind','bytes','revision'})
        encoded=json.dumps(events); self.assertNotIn('Crack beside the lens',encoded); self.assertNotIn('note',encoded)
        self.assertNotIn('payload',encoded)

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--port',type=int,default=8891); parser.add_argument('--output',type=pathlib.Path,required=True)
    args=parser.parse_args(); PORT=args.port
    if args.output.exists():raise SystemExit('Refusing to overwrite an evidence directory')
    args.output.mkdir(parents=True)
    source=(ROOT/'fixture.py').read_bytes(); (args.output/'fixture-source.py').write_bytes(source)
    OWNER=FixtureOwner(args.output); result=None
    try:
        OWNER.start()
        with (args.output/'unittest.txt').open('w') as stream:
            result=unittest.TextTestRunner(stream=stream,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(FixtureContractTest))
    finally:
        if OWNER.process and OWNER.process.poll() is None: OWNER.stop()
    report={'passed':result.wasSuccessful(),'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped),
        'sourceSha256':hashlib.sha256(source).hexdigest(),'sourceUnchangedDuringRun':source==(ROOT/'fixture.py').read_bytes(),
        'testSha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'port':PORT,'stages':STAGES,
        'eventCount':sum(len(stage['events']) for stage in STAGES),'ownedProcesses':OWNER.starts,
        'scope':'Isolated loopback HTTP, SQLite transactions and graceful service restart; no Android/process-kill/power-loss/storage-security claim'}
    raw_events=[json.loads(line) for line in (args.output/'fixture-events.jsonl').read_text().splitlines()]
    report['rawEventCount']=len(raw_events)
    report['stageEventsMatchRawLog']=raw_events==[event for stage in STAGES for event in stage['events']]
    report['passed']=report['passed'] and report['sourceUnchangedDuringRun'] and report['stageEventsMatchRawLog'] and all(p.get('exitCode')==0 for p in OWNER.starts)
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:value for key,value in report.items() if key!='stages'}));sys.exit(0 if report['passed'] else 1)
