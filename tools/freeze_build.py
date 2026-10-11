#!/usr/bin/env python3
"""Bind the implementation to all signed app variants, test APKs and exact R8 mappings."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,subprocess
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def copy(p,q):
 q.parent.mkdir(parents=True,exist_ok=True);subprocess.run(['cp','-c',str(p),str(q)],check=True);assert sha(p)==sha(q)
sources={}
for directory,dirs,names in os.walk(ROOT):
 dirs[:]=[d for d in dirs if d not in ['build','.gradle','.kotlin','evidence','__pycache__','.git']]
 for name in names:
  f=Path(directory)/name
  if name in ['local.properties','README.md','verification.md'] or name.endswith('.log'):continue
  rel=f.relative_to(ROOT);copy(f,out/'source'/rel);sources[str(rel)]=sha(f)
paths=list((ROOT/'app/build/outputs/apk').rglob('*.apk'))
paths += [p for p in (ROOT/'app/build/outputs/mapping').rglob('*') if p.is_file()]
assert len(list((ROOT/'app/build/outputs/apk').rglob('*-release.apk')))==1
artifacts={}
for f in paths:
 q=out/'artifacts'/f.relative_to(ROOT);copy(f,q);artifacts[str(q.relative_to(ROOT))]=sha(f)
(out/'manifest.json').write_text(json.dumps({'status':'SOURCE_AND_ARTIFACT_IDENTITY_ONLY','created_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sources':sources,'artifacts':artifacts},indent=2)+'\n');print({'sources':len(sources),'artifacts':len(artifacts)})
