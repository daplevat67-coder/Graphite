"""Prepare isolated emulator tooling in workspace/toolchain; downloads are SHA-pinned and staged in /tmp."""
from pathlib import Path
import urllib.request as U,json,hashlib,zipfile,tarfile,xml.etree.ElementTree as E,concurrent.futures,subprocess,os,re,shutil,tempfile
r=Path(__file__).resolve().parents[3]/'toolchain';sdk=r/'android-sdk'
def fetch(url,path,expected=None,algorithm='sha256'):
 if path.exists() and (not expected or hashlib.new(algorithm,path.read_bytes()).hexdigest()==expected):return path
 fd,name=tempfile.mkstemp(prefix='graphite-lab-download-',dir='/tmp');os.close(fd);staging=Path(name)
 try:
  with U.urlopen(url,timeout=120) as q,staging.open('wb') as f:
   while c:=q.read(1024*1024):f.write(c)
  if expected:assert hashlib.new(algorithm,staging.read_bytes()).hexdigest()==expected,(path.name,'hash mismatch')
  shutil.copyfile(staging,path)
 finally:staging.unlink(missing_ok=True)
 return path
def unzip(path,d):
 with zipfile.ZipFile(path) as z:
  z.extractall(d)
  for item in z.infolist():
   p=d/item.filename;mode=item.external_attr>>16
   if p.is_file() and mode:p.chmod(mode&0o777)
def package(xml,path):
 root=E.fromstring(U.urlopen(xml,timeout=60).read())
 for p in root.findall('remotePackage'):
  if p.get('path')!=path:continue
  for a in p.findall('./archives/archive'):
   if a.findtext('host-os') not in (None,'linux'):continue
   return a.findtext('complete/url'),a.findtext('complete/checksum')
 raise RuntimeError(path)
def emulator():
 d=sdk/'emulator'
 if not (d/'emulator').exists():
  u,h=package('https://dl.google.com/android/repository/repository2-1.xml','emulator')
  p=fetch('https://dl.google.com/android/repository/'+u,r/'xray-emulator.zip',h,'sha1')
  unzip(p,sdk)
 # Some Linux emulator ZIP entries omit execute bits for launchers.
 for binary in (d/'emulator',d/'crashpad_handler'):
  if binary.is_file():binary.chmod(binary.stat().st_mode|0o111)
 print('emulator ready',flush=True)
def platform_tools():
 if (sdk/'platform-tools/adb').exists():return
 u,h=package('https://dl.google.com/android/repository/repository2-1.xml','platform-tools');p=fetch('https://dl.google.com/android/repository/'+u,r/'xray-platform-tools.zip',h,'sha1');unzip(p,sdk);print('platform tools ready',flush=True)
def image():
 if (sdk/'system-images/android-28/default/x86_64/system.img').exists():return
 base='https://dl.google.com/android/repository/sys-img/android/';u,h=package(base+'sys-img2-3.xml','system-images;android-28;default;x86_64');p=fetch(base+u,r/'xray-system-image.zip',h,'sha1');d=sdk/'system-images/android-28/default';d.mkdir(parents=True,exist_ok=True);unzip(p,d);print('system image ready',flush=True)
def core():
 if (r/'xray-lab-core/xray').exists():return
 info=json.load(U.urlopen('https://api.github.com/repos/XTLS/Xray-core/releases/tags/v26.10.31',timeout=60));a=next(x for x in info['assets'] if x['name']=='Xray-linux-64.zip');p=fetch(a['browser_download_url'],r/'xray-lab-core.zip',a['digest'].split(':')[1]);d=r/'xray-lab-core';d.mkdir(exist_ok=True);unzip(p,d);(d/'xray').chmod(0o755);print('standalone core ready',flush=True)
def go():
 if Path('/tmp/graphite-xray-lab-go/go/bin/go').exists():return
 versions=json.load(U.urlopen('https://go.dev/dl/?mode=json&include=all',timeout=60));v=next(x for x in versions if x['version']=='go1.25.0');a=next(x for x in v['files'] if x['filename']=='go1.25.0.linux-amd64.tar.gz');p=fetch('https://go.dev/dl/'+a['filename'],r/'xray-lab-go.tar.gz',a['sha256'])
 with tarfile.open(p) as z:z.extractall('/tmp/graphite-xray-lab-go')
 print('Go fixture compiler ready',flush=True)
def xkb():
 if (r/'emulator-libs/usr/lib/x86_64-linux-gnu/libxkbfile.so.1').exists():return
 base='https://archive.ubuntu.com/ubuntu/pool/main/libx/libxkbfile/';listing=U.urlopen(base,timeout=60).read().decode();names=re.findall(r'href="(libxkbfile1_[^"]+_amd64.deb)"',listing);assert names;p=fetch(base+names[-1],r/'xray-libxkbfile.deb');d=r/'emulator-libs';d.mkdir(exist_ok=True);subprocess.run(['dpkg-deb','-x',str(p),str(d)],check=True);print('scoped emulator library ready',flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
 for f in [pool.submit(fn) for fn in (emulator,platform_tools,image,core,go,xkb)]:f.result()
source=Path(__file__).with_name('http-fixture.go')
env=os.environ.copy();env.update(CGO_ENABLED='0',GOOS='linux',GOARCH='amd64',GOCACHE='/tmp/graphite-xray-lab-gocache')
subprocess.run(['/tmp/graphite-xray-lab-go/go/bin/go','build','-trimpath','-ldflags=-s -w','-o',str(r/'xray-lab-http'),str(source)],env=env,check=True)
print('XRAY_LAB_TOOLCHAIN_READY',flush=True)
