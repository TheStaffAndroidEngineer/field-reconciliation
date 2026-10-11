#!/usr/bin/env python3
"""C28 guarded native acquisition. Only root invokes this device-operating helper.

An explicit per-configuration app/fixture reset starts each isolated appearance
journey. Within each journey, restart never clears the saved inspection. Evidence
captures are not acceptance until independently inspected.
"""
from pathlib import Path
import argparse, hashlib, json, re, sys, time, urllib.request
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'projects/commerce-app/tools'))
from capture_checkpoint_appearance import CaptureSession, area, bounds, matches, panes, pane_key, find_pane, signature
from fresh_android_xml import read_ui_xml
APP = 'staff.android.field'
PORT = 'tcp:8890'
BASE = dict(uploadHttp=200, reportHttp=200, lookupHttp=200, dropUploadOnce=False,
    dropReportOnce=False, delayMs=0, schema=1)
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()

class FieldCapture(CaptureSession):
    def swipe(self, pane, direction):
        left, top, right, bottom = bounds(pane)
        if not area((left, top, right, bottom)):
            raise RuntimeError('Fresh pane is empty.')
        high, low = top + (bottom-top)*35//100, top + (bottom-top)*65//100
        start, end = (low, high) if direction == 'down' else (high, low)
        self.adb('shell', 'input', 'swipe', (left+right)//2, start, (left+right)//2, end, 180)
        return self.settled()

    def failure_witness(self):
        result = {'scope': 'Unsettled failure viewport before restoration; not acceptance.', 'errors': {}}
        for name, producer in [
            ('xml', lambda: read_ui_xml(self.adb, '/sdcard/common-goods-failure.xml', {APP, 'android', 'com.android.systemui'}, attempts=1).encode()),
            ('png', lambda: self.adb('exec-out', 'screencap', '-p')),
            ('window', lambda: self.adb('shell', 'dumpsys', 'window', 'windows')),
        ]:
            try:
                data = producer(); path = self.args.output / ('failure-viewport.' + ('txt' if name == 'window' else name))
                path.write_bytes(data); result[name] = path.name; result[name+'Sha256'] = sha(path)
            except Exception as error:
                result['errors'][name] = str(error)
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', '--output', dest='output', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--expected-avd', default='StaffAndroid_API_36')
    parser.add_argument('--candidate', default='field-release')
    parser.add_argument('--expected-version', type=int, default=2801)
    parser.add_argument('--configs', nargs='+', choices=['light','dark','large','wide'], default=['light','dark','large','wide'])
    parser.add_argument('--max-scrolls', type=int, default=18)
    parser.add_argument('--settle-timeout', type=float, default=40)
    parser.add_argument('--current-assessment-branch', action='store_true', help='After the ordinary light journey, explicitly reset once more to inspect Use current assessment. The extra reset is recorded.')
    args=parser.parse_args();args.package=APP
    if not 1<=args.max_scrolls<=24 or not 1<=args.settle_timeout<=120:parser.error('Use bounded capture limits.')
    apk=args.apk.resolve()
    if not apk.is_file():parser.error('APK does not exist.')
    if args.output.exists():parser.error('Use a fresh evidence directory; prior attempts must be retained.')
    args.output.mkdir(parents=True)
    source=Path(__file__).read_bytes();(args.output/'capture-script.py').write_bytes(source)
    dependencies=[ROOT/'projects/commerce-app/tools/capture_checkpoint_appearance.py',ROOT/'projects/catalog-network/tools/fresh_android_xml.py']
    report={'status':'STARTING','package':APP,'serial':args.serial,'candidate':args.candidate,
        'apk':str(apk),'apkSha256':sha(apk),'expectedVersionCode':args.expected_version,
        'captureScriptSha256':hashlib.sha256(source).hexdigest(),
        'captureDependencies':{str(p.relative_to(ROOT)):sha(p) for p in dependencies},
        'rows':[],'fixtureActions':[],'resets':[],'restorationErrors':[],
        'scope':'Four display configurations of the optimized Common Goods Field inspection journey. Deliberate task-owned package and fixture reset per configuration; saved evidence survives force-stop/relaunch inside each journey. Controls/display/reverse restored; fixture business records deliberately changed by these isolated scenarios, not restored. Actual pixel review is separate from acquisition and runtime correctness.'}
    session=FieldCapture(args);original=original_reverse=original_fixture=None;failure=None
    def save():(args.output/'result.json').write_text(json.dumps(report,indent=2)+'\n')
    def admin(path,payload=None):
        request=urllib.request.Request('http://127.0.0.1:8890'+path,data=None if payload is None else json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=10) as response:value=json.load(response)
        report['fixtureActions'].append({'path':path,'payload':payload,'hostMonotonicNanos':time.monotonic_ns()});return value
    def configure(**changes):
        expected=dict(BASE,**changes);actual=admin('/admin/config',expected)
        if actual!=expected:raise RuntimeError('Fixture did not apply exact requested controls.')
        return actual
    def snapshot(name):
        value=admin('/admin/state');path=args.output/(name+'-fixture.json');path.write_text(json.dumps(value,indent=2)+'\n')
        return {'path':path.name,'sha256':sha(path),'control':value['control'],'events':len(value['events']),'current':value['current'],'photoCount':value['photoCount'],'reportCount':value['reportCount']}
    def unavailable_report_attempt(after_sequence=0, identity=None):
        deadline=time.monotonic()+120
        while True:
            value=admin('/admin/state')
            attempts=[e for e in value['events'] if e.get('route')=='report_lookup' and e.get('kind')=='unavailable']
            identities={e.get('identity') for e in attempts}
            if len(identities)>1:raise RuntimeError('Expected one immutable operation before resolution, found '+repr(identities))
            candidates=[e for e in attempts if e['sequence']>after_sequence and (identity is None or e.get('identity')==identity)]
            if candidates:
                event=candidates[-1]
                if not re.fullmatch('[a-f0-9]{32}',event.get('identity','')):raise RuntimeError('Invalid observed operation identity.')
                return event
            if time.monotonic()>=deadline:raise RuntimeError('No fresh unavailable lookup for the same saved operation within120seconds.')
            time.sleep(.5)
    def has(raw,label):return any(n.get('package')==APP and matches(n,label) for n in ET.fromstring(raw).iter('node'))
    def framed(raw,labels):
        tree=ET.fromstring(raw);parents={c:p for p in tree.iter() for c in p}
        for label in labels:
            found=False
            for node in tree.iter('node'):
                if node.get('package')!=APP or not matches(node,label):continue
                left,top,right,bottom=bounds(node)
                if not area((left,top,right,bottom)):continue
                valid=True;parent=node
                while parent in parents:
                    parent=parents[parent]
                    if parent.get('scrollable')=='true':
                        pl,pt,pr,pb=bounds(parent)
                        if left<pl or right>pr or top<pt+12 or bottom>pb-12:valid=False
                if valid:found=True;break
            if not found:return False
        return True
    def reveal(raw,labels,required=True):
        labels=[labels] if isinstance(labels,str) else labels
        if framed(raw,labels):return raw
        for key in [pane_key(p) for p in panes(ET.fromstring(raw),APP)]:
            for direction in ['down','up']:
                for _ in range(args.max_scrolls):
                    pane=find_pane(ET.fromstring(raw),APP,key)
                    if pane is None:break
                    before=signature(pane);raw=session.swipe(pane,direction)
                    if framed(raw,labels):return raw
                    after=find_pane(ET.fromstring(raw),APP,key)
                    if after is None or signature(after)==before:break
        if required:raise RuntimeError('Could not fully frame '+repr(labels))
        return raw
    def tap(label,raw,settle=True):
        raw=reveal(raw,label);raw=session.xml();target=session.target(ET.fromstring(raw),label)
        if target is None:raise RuntimeError('Fresh guarded enabled target missing: '+label)
        left,top,right,bottom=bounds(target);session.adb('shell','input','tap',(left+right)//2,(top+bottom)//2)
        return session.settled() if settle else session.xml()
    def capture(row,name,raw,condition):
        frame=session.capture(row['config']+'-'+name,raw)
        frame.update(condition=condition,surfacePackage=APP,fixtureSnapshot=snapshot(row['config']+'-'+name))
        row['frames'].append(frame);save();return raw
    def view(row,name,raw,labels,condition):return capture(row,name,reveal(raw,labels),condition)
    def stage(raw,value):
        deadline=time.monotonic()+args.settle_timeout
        while True:
            raw=reveal(raw,['status_'+value,'status_detail'],required=False)
            if framed(raw,['status_'+value,'status_detail']):return raw
            if time.monotonic()>=deadline:raise RuntimeError('Expected stable inspection stage '+value+' did not arrive.')
            time.sleep(.3);raw=session.settled()
    def launch(open_inspection=False):
        command=['shell','am','start','-W','-n',APP+'/.MainActivity','-f','0x10008000']
        if open_inspection:command+=['--ez','open_inspection','true']
        session.adb(*command);return session.settled(exact=['inspection' if open_inspection else 'collections'])
    def reset(row,branch):
        before=snapshot(row['config']+'-'+branch+'-before-reset')
        session.adb('shell','am','force-stop',APP)
        answer=session.adb('shell','pm','clear',APP).decode().strip()
        if answer!='Success':raise RuntimeError('Explicit isolated app reset failed: '+answer)
        admin('/admin/reset',{});configure(lookupHttp=503)
        report['resets'].append({'configuration':row['config'],'branch':branch,'package':APP,'result':answer,'beforeFixture':before,'reason':'Explicit reset of this task-owned sample before a separate appearance journey; not a restart/durability intervention.'});save()
    def filled_form(row,raw,prefix='',detailed=True):
        raw=tap('open_inspection',raw);raw=session.settled(exact=['inspection'])
        if detailed:
            raw=view(row,prefix+'inspection-top',raw,'visit_scope','Worker is inspecting assigned Camera CG-204; the field role and visit identity are explicit.')
            raw=view(row,prefix+'condition',raw,['condition_good','condition_damaged'],'Both condition choices are fully framed, with Damaged selected.')
            raw=view(row,prefix+'note',raw,'inspection_note','The complete provided note is editable; its viewport is separate from other controls at large text.')
        raw=reveal(raw,'inspection_note')
        notes=[n.get('text','') for n in ET.fromstring(raw).iter('node') if n.get('class')=='android.widget.EditText']
        if notes!=['Crack beside the lens']:raise RuntimeError('Unexpected fixture note: '+repr(notes))
        raw=tap('attach_photo',raw)
        if detailed:
            raw=view(row,prefix+'photo-selected',raw,'photo_selected','Selected sample evidence is explicitly labeled and will be saved with the inspection.')
            raw=view(row,prefix+'save-action',raw,'save_local','The complete Save on this device action is framed before the local transaction.')
        raw=tap('save_local',raw);return stage(raw,'queued')
    def conflict(row,raw,prefix=''):
        configure(reportHttp=503);raw=tap('send_again',raw);raw=stage(raw,'uploaded')
        raw=capture(row,prefix+'photo-sent-report-pending',raw,'The photo is accepted while the report remains unfinished; this is not Submitted.')
        current=admin('/admin/advance',{})
        if current['revision']!=8:raise RuntimeError('Expected synthetic assessment revision8.')
        configure();raw=tap('send_again',raw);raw=stage(raw,'needsreview')
        raw=capture(row,prefix+'needs-review',raw,'The remote assessment advanced; Needs review retains the saved evidence.')
        raw=view(row,prefix+'local-assessment',raw,['local_assessment','Reviewed revision 7'],'Own Damaged assessment and Crack beside the lens note remain intact with the reviewed revision.')
        raw=view(row,prefix+'server-assessment',raw,['server_assessment','Current revision 8'],'The current Good assessment and Rechecked at the depot note are displayed separately.')
        raw=view(row,prefix+'keep-action',raw,'keep_mine','Keep my inspection is a fully framed explicit resolution action.')
        raw=view(row,prefix+'current-action',raw,'use_current','Use current assessment is a separate fully framed resolution choice.')
        return raw
    def sending(row,raw):
        configure(delayMs=3000,lookupHttp=503)
        raw=reveal(raw,['status_queued','send_again'],required=False)
        raw=tap('send_again',raw,settle=False)
        raw=reveal(raw,'sending',required=False)
        observed=framed(raw,['sending'])
        if observed:
            raw=capture(row,'sending',raw,'Actual in-flight worker indication during a bounded delayed unavailable lookup; stored inspection remains visible. This frame is not a timing measurement.')
            row['sendingAcquisition']={'observed':True,'frame':row['config']+'-sending'}
        else:
            witness=session.capture(row['config']+'-sending-missed',raw)
            row['sendingAcquisition']={'observed':False,'diagnostic':witness,'reason':'Transient not fully framed before it ended; excluded from accepted frames.'}
        configure(lookupHttp=503);save();return stage(session.settled(),'queued')
    try:
        avd=session.adb('emu','avd','name').decode().strip()
        if args.expected_avd not in avd.splitlines():raise RuntimeError('Unexpected emulator: '+avd)
        report['avd']=avd;original=session.settings();report['originalSettings']=original
        original_reverse=session.adb('reverse','--list').decode();report['originalReverse']=original_reverse
        mappings=[p for p in map(str.split,original_reverse.splitlines()) if len(p)>=3 and p[-2]==PORT]
        if len(mappings)>1:raise RuntimeError('Ambiguous original reverse mapping.')
        original_fixture=admin('/admin/state')['control'];report['originalFixtureState']=original_fixture
        session.adb('reverse',PORT,PORT);session.adb('install','-r',apk)
        installed=session.adb('shell','pm','path',APP).decode().strip().removeprefix('package:')
        session.adb('pull',installed,args.output/'installed.apk')
        if sha(args.output/'installed.apk')!=report['apkSha256']:raise RuntimeError('Installed APK differs from selected artifact.')
        package_dump=session.adb('shell','dumpsys','package',APP);(args.output/'installed-package.txt').write_bytes(package_dump)
        report['installedPackageSha256']=hashlib.sha256(package_dump).hexdigest()
        if not re.search(rb'versionCode='+str(args.expected_version).encode()+rb'\b',package_dump):raise RuntimeError('Unexpected app version.')
        for config in args.configs:
            session.adb('shell','am','force-stop',APP)
            session.adb('shell','wm','size','1920x1200' if config=='wide' else 'reset')
            session.adb('shell','wm','density','240' if config=='wide' else 'reset')
            session.adb('shell','settings','put','system','font_scale','2.0' if config=='large' else '1.0')
            session.adb('shell','cmd','uimode','night','yes' if config=='dark' else 'no')
            row={'config':config,'settings':session.settings(),'status':'STARTING','frames':[]};report['rows'].append(row)
            assert float(row['settings']['font'])==(2 if config=='large' else 1)
            assert 'Night mode: '+('yes' if config=='dark' else 'no') in row['settings']['night']
            if config=='wide':assert 'Override size: 1920x1200' in row['settings']['size'] and 'Override density: 240' in row['settings']['density']
            else:assert 'Override' not in row['settings']['size'] and 'Override' not in row['settings']['density']
            reset(row,'keep');raw=launch()
            raw=capture(row,'collections-top',raw,'Common Goods Field Collections establishes the worker role and retains the original retail identity.')
            raw=view(row,'collection-entry',raw,'open_inspection','The full Camera CG-204 collection action is reachable and readable.')
            raw=filled_form(row,raw);raw=capture(row,'saved-local',raw,'Saved on this device confirms local durability while the service lookup is unavailable.')
            raw=view(row,'saved-assessment',raw,'saved_assessment','The locally saved condition and note remain visible before server acceptance.')
            raw=view(row,'retained-photo',raw,['saved_photo','photo_retained'],'The actual saved sample photograph and retained-on-device explanation are fully framed.')
            prior_attempt=unavailable_report_attempt()
            row['restartBefore']=snapshot(config+'-restart-before')
            row['pidBeforeRestart']=session.adb('shell','pidof',APP).decode().strip()
            session.adb('shell','am','force-stop',APP)
            stopped=admin('/admin/state');floor=max((e['sequence'] for e in stopped['events']),default=0)
            row['restartStopped']=snapshot(config+'-restart-stopped')
            raw=launch(True)
            row['pidAfterRestart']=session.adb('shell','pidof',APP).decode().strip()
            if not row['pidBeforeRestart'] or not row['pidAfterRestart'] or row['pidBeforeRestart']==row['pidAfterRestart']:raise RuntimeError('A distinct relaunched app process was not observed.')
            resumed_attempt=unavailable_report_attempt(floor,prior_attempt['identity'])
            row['restartAfter']=snapshot(config+'-restart-after')
            row['restartOperationWitness']={'operationId':prior_attempt['identity'],'beforeEvent':prior_attempt,'stoppedSequenceFloor':floor,'afterEvent':resumed_attempt,'sameUniqueOperation':True,'waitLimitSeconds':120}
            raw=stage(session.settled(),'queued');raw=capture(row,'saved-after-restart',raw,'Force-stop/relaunch preserved the saved inspection and a subsequent unavailable lookup used the same operation identity from the distinct app process; no app data was cleared during restart.')
            raw=sending(row,raw);raw=conflict(row,raw)
            configure(reportHttp=503);raw=tap('keep_mine',raw);raw=stage(raw,'uploaded')
            raw=capture(row,'resolved-report-pending',raw,'An explicit Keep my inspection creates a new pending report against the reviewed revision; photo success still is not submission.')
            configure();raw=tap('send_again',raw);raw=stage(raw,'submitted')
            raw=view(row,'submitted',raw,['status_submitted','accepted_revision'],'Authoritative report receipt is now visible; this is the submitted state.')
            raw=view(row,'submitted-evidence',raw,['saved_photo','photo_retained'],'Submitted inspection retains its local evidence until an explicit removal choice.')
            raw=view(row,'remove-action',raw,'release_photo','Remove local photo is available only after the inspection is resolved.')
            raw=tap('release_photo',raw);raw=view(row,'photo-removed',raw,['photo_removed','return_collections'],'Releasing this record’s local photo reference leaves its inspection record and explains that other records may retain the same photo.')
            if config=='light' and args.current_assessment_branch:
                reset(row,'current-assessment');raw=launch();raw=filled_form(row,raw,prefix='current-',detailed=False)
                raw=conflict(row,raw,prefix='current-');raw=tap('use_current',raw);raw=stage(raw,'superseded')
                raw=capture(row,'current-assessment-kept',raw,'Explicit Use current assessment keeps the service view; the earlier local report is not labeled Submitted.')
                raw=view(row,'current-kept-server',raw,'server_assessment','The kept current assessment is prominent and contains the server Good condition and depot note.')
                raw=view(row,'current-kept-history',raw,'saved_assessment','Earlier Damaged condition and the worker note remain explicitly labeled as local inspection history.')
                raw=view(row,'current-kept-evidence',raw,['saved_photo','photo_retained'],'The earlier evidence remains retained after choosing the current assessment.')
            row['endFixture']=snapshot(config+'-end');row['status']='CAPTURED_NOT_REVIEWED';save();print(config+' captured',flush=True)
        report['status']='CAPTURED_NOT_REVIEWED'
    except Exception as error:
        failure=error;report['status']='FAILED';report['error']=str(error)
        try:report['failureWitness']=session.failure_witness()
        except Exception as witness_error:report['failureWitness']={'error':str(witness_error)}
    finally:
        restore=[('shell','am','force-stop',APP)]
        if original:
            restore.append(('shell','settings','delete','system','font_scale') if original['font'] in ('null','') else ('shell','settings','put','system','font_scale',original['font']))
            restore.append(('shell','cmd','uimode','night',original['night'].split(':')[-1].strip()))
            for kind in ['size','density']:
                found=re.search(r'Override '+kind+r':\s*(\S+)',original[kind],re.I);restore.append(('shell','wm',kind,found.group(1) if found else 'reset'))
        for command in restore:
            try:session.adb(*command)
            except Exception as error:report['restorationErrors'].append(str(error))
        if original_reverse is not None:
            try:
                current=session.adb('reverse','--list').decode()
                if any(len(p)>=3 and p[-2]==PORT for p in map(str.split,current.splitlines())):session.adb('reverse','--remove',PORT)
                for parts in map(str.split,original_reverse.splitlines()):
                    if len(parts)>=3 and parts[-2]==PORT:session.adb('reverse',parts[-2],parts[-1])
                report['restoredReverse']=session.adb('reverse','--list').decode()
                if sorted(report['restoredReverse'].splitlines())!=sorted(original_reverse.splitlines()):report['restorationErrors'].append('Reverse mappings differ.')
            except Exception as error:report['restorationErrors'].append(str(error))
        if original_fixture is not None:
            try:
                report['restoredFixtureState']=admin('/admin/config',original_fixture)
                if report['restoredFixtureState']!=original_fixture:report['restorationErrors'].append('Fixture controls differ.')
            except Exception as error:report['restorationErrors'].append(str(error))
        try:
            report['restoredSettings']=session.settings()
            if original and report['restoredSettings']!=original:report['restorationErrors'].append('Display settings differ.')
        except Exception as error:report['restorationErrors'].append(str(error))
        if report['restorationErrors'] and not failure:report['status']='CAPTURED_RESTORE_FAILED'
        save();(args.output/'commands.json').write_text(json.dumps(session.commands,indent=2)+'\n');(args.output/'tap-guards.json').write_text(json.dumps(session.tap_guards,indent=2)+'\n')
    if failure:raise failure
    if report['restorationErrors']:raise RuntimeError('Restoration errors; inspect result.json.')

if __name__=='__main__':main()
