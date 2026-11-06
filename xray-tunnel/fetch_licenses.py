"""Collect full upstream sources/licenses matching the pinned native Android release."""
from pathlib import Path
import urllib.request as U,hashlib,json,concurrent.futures,re,zipfile,io,tarfile
HERE=Path(__file__).resolve().parent;ASSETS=HERE/'src/main/assets/licenses';ASSETS.mkdir(parents=True,exist_ok=True);UP=HERE/'upstream';UP.mkdir(exist_ok=True)
def download(url):return U.urlopen(url,timeout=60).read()
for repo,tag,name in [('XTLS/libXray','v26.10.31','libXray'),('XTLS/Xray-core','v26.10.31','Xray-core'),('heiher/hev-socks5-tunnel','2.19.3','Hev')]:
 p=ASSETS/(name+'-LICENSE.txt')
 if not p.exists():p.write_bytes(download('https://raw.githubusercontent.com/'+repo+'/'+tag+'/LICENSE'))
 p=UP/(name+'-'+tag+'-source.tar.gz')
 if not p.exists():p.write_bytes(download('https://api.github.com/repos/'+repo+'/tarball/'+tag))
 (UP/(name+'-'+tag+'-source.sha256')).write_text(hashlib.sha256(p.read_bytes()).hexdigest()+'\n')
 print(name,'source/license ready',flush=True)
p=ASSETS/'Go-LICENSE.txt'
if not p.exists():p.write_bytes(download('https://raw.githubusercontent.com/golang/go/go1.27.3/LICENSE'))
p=UP/'Hev-2.19.3-full-source.tar.xz'
if not p.exists():p.write_bytes(download('https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.3/hev-socks5-tunnel-2.19.3.tar.xz'))
assert hashlib.sha256(p.read_bytes()).hexdigest()=='8aaa33b344cab03a5cfc3675b9aaae55c6ee6088d78678ad1fbb44855b5ba570'
texts=[]
with tarfile.open(p,'r:xz') as z:
 for f in z.getmembers():
  if f.isfile() and f.name.rsplit('/',1)[-1].lower() in ('license','license.md','license.txt','copying','copying.md','notice'):
   try:text=z.extractfile(f).read().decode()
   except UnicodeDecodeError:continue
   texts.append('File: '+f.name+'\n'+text)
(ASSETS/'Hev-bundled-dependency-LICENSES.txt').write_text('\n\n'.join(texts))
modules=[]
for line in (HERE/'native-buildinfo.txt').read_text().splitlines():
 p=line.split('\t')
 if len(p)>2 and p[0]=='dep':modules.append((p[1],p[2]))
def project(m):
 if m.startswith('github.com/'):return '/'.join(m.split('/')[1:3])
 if m.startswith('golang.org/x/'):return 'golang/'+m.split('/')[2]
 if m.startswith('google.golang.org/grpc'):return 'grpc/grpc-go'
 if m.startswith('google.golang.org/protobuf'):return 'protocolbuffers/protobuf-go'
 if m.startswith('google.golang.org/genproto'):return 'googleapis/go-genproto'
 return {'gvisor.dev/gvisor':'google/gvisor','go4.org/netipx':'go4org/netipx','lukechampine.com/blake3':'lukechampine/blake3','layeh.com/gopher-luar':'layeh/gopher-luar','go.yaml.in/yaml/v3':'yaml/go-yaml','gopkg.in/yaml.v2':'go-yaml/yaml','golang.zx2c4.com/wireguard':'WireGuard/wireguard-go','golang.zx2c4.com/wintun':'WireGuard/wintun','golang.zx2c4.com/wireguard/windows':'WireGuard/wireguard-windows'}.get(m)
def collect(item):
 module,version=item
 if module=='github.com/xtls/libxray':return dict(module=module,version=version,licenses='libXray-LICENSE.txt',source='https://github.com/XTLS/libXray/tree/v26.10.31',note='Native binary local replacement; official archive pinned to v26.10.31.')
 filename=re.sub('[^a-zA-Z0-9.-]+','_',module)+'-LICENSES.txt';dest=ASSETS/filename
 if dest.exists():return dict(module=module,version=version,licenses=filename)
 repo=project(module);match=re.search(r'\d{14}-([0-9a-f]+)$',version);ref=match[1] if match else version
 if repo:
  for leaf in ['LICENSE','LICENSE.md','LICENSE.txt','COPYING']:
   url='https://raw.githubusercontent.com/'+repo+'/'+ref+'/'+leaf
   try:body=download(url).decode();dest.write_text('Module: '+module+'\nVersion: '+version+'\nSource: '+url+'\n\n'+body);return dict(module=module,version=version,licenses=filename,source=url)
   except Exception:pass
 # Go module archives also carry the root license for submodules.
 escape=lambda x:''.join('!'+c.lower() if c.isupper() else c for c in x)
 url='https://proxy.golang.org/'+escape(module)+'/@v/'+escape(version)+'.zip'
 try:
  texts=[]
  with zipfile.ZipFile(io.BytesIO(download(url))) as z:
   for name in z.namelist():
    if name.rsplit('/',1)[-1].lower().startswith(('license','copying')):
     try:t=z.read(name).decode()
     except UnicodeDecodeError:continue
     if len(t)<=100000:texts.append('File: '+name+'\n'+t)
  if texts:dest.write_text('Module: '+module+'\nVersion: '+version+'\nSource: '+url+'\n\n'+'\n\n'.join(texts));return dict(module=module,version=version,licenses=filename,source=url)
 except Exception:pass
 return dict(module=module,version=version,error='License unavailable')
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
 results=[]
 for result in pool.map(collect,modules):results.append(result);print(result['module'],result.get('error','OK'),flush=True)
(ASSETS/'Go-module-licenses.json').write_text(json.dumps(results,indent=2))
assert all('error' not in r for r in results),'Some linked module licenses require review'
print('ALL_XRAY_LICENSES_READY',len(results),flush=True)
