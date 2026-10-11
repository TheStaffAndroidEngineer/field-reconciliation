#!/usr/bin/env python3
"""Execute explicit correctness classes against exact retained debug artifacts."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tarfile,io,re
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--app',type=Path,required=True);p.add_argument('--test',type=Path,required=True);p.add_argument('--serial',default='emulator-5554');p.add_argument('--classes',default='staff.android.field.FieldStoreTest,staff.android.field.FieldHttpTest');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb');report={'status':'STARTING','commands':[],'apks':{},'scope':'Actual field inspection storage, photo/report HTTP and native state on the recorded emulator; no physical timing or production reliability claim.'}
def run(*args):
 proc=subprocess.run([adb,'-s',a.serial,*map(str,args)],capture_output=True,timeout=300);report['commands'].append({'args':list(map(str,args)),'returncode':proc.returncode,'stderr':proc.stderr.decode(errors='replace')});assert proc.returncode==0,proc.stderr.decode(errors='replace');return proc.stdout
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
try:
 for key,path,pkg in [('app',a.app,'staff.android.field'),('test',a.test,'staff.android.field.test')]:
  report['apks'][key]={'path':str(path.resolve()),'sha256':sha(path),'install':run('install','-r',path).decode()}
  actual=run('shell','pm','path',pkg).decode().strip().removeprefix('package:');q=a.out/(key+'-installed.apk');run('pull',actual,q);assert sha(q)==sha(path)
 report['priorDiagnosticFiles'] = []
 names = run('shell', 'run-as', 'staff.android.field', 'sh', '-c', "'if [ -d files ]; then ls -1 files; fi'").decode().splitlines()
 for name in names:
  if not re.fullmatch(r'field-[A-Za-z0-9-]+\.json', name): continue
  payload = run('exec-out', 'run-as', 'staff.android.field', 'cat', 'files/' + name)
  destination = a.out/'prior-device-diagnostics'/name; destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(payload)
  report['priorDiagnosticFiles'].append({'path': str(destination), 'sha256': sha(destination), 'scope': 'Retained before clearing only this old diagnostic file; not a current campaign execution.'})
  run('shell', 'run-as', 'staff.android.field', 'rm', 'files/' + name)
 report['device']={k:run('shell','getprop',k).decode().strip() for k in ['ro.product.model','ro.product.cpu.abi','ro.build.version.sdk','ro.build.version.release','ro.build.fingerprint']}
 report['originalReverse']=run('reverse','--list').decode();run('reverse','tcp:8890','tcp:8890')
 instrumentation=run('shell','am','instrument','-w','-e','class',a.classes,'staff.android.field.test/androidx.test.runner.AndroidJUnitRunner');(a.out/'instrumentation.txt').write_bytes(instrumentation)
 report['classes']=a.classes;report['testCount']=int(re.search(rb'OK \((\d+) tests?\)',instrumentation).group(1)) if re.search(rb'OK \((\d+) tests?\)',instrumentation) else None
 archive=run('exec-out','run-as','staff.android.field','tar','-cf','-','files');(a.out/'private-reports.tar').write_bytes(archive)
 with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
  for item in tar:
   path=Path(item.name)
   if not item.isfile() or path.suffix!='.json':continue
   assert path.parts[0]=='files' and '..' not in path.parts
   raw=tar.extractfile(item).read();json.loads(raw);q=a.out/'private-reports'/path;q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(raw)
 assert report['testCount'] is not None and b'FAILURES!!!' not in instrumentation
 report['status']='PASS'
except Exception as failure:
 report['status']='FAILED';report['error']=str(failure);raise
finally:
 if 'originalReverse' in report:
  run('reverse','--remove','tcp:8890')
  for line in report['originalReverse'].splitlines():
   parts=line.split()
   if len(parts)>=3 and parts[-2]=='tcp:8890':run('reverse',parts[-2],parts[-1])
 (a.out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print({'status':report['status'],'tests':report.get('testCount')})
