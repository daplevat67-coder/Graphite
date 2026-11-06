"""Reconstruct pinned Xray/JAR payloads, then build Hev upstream source for Android26."""
from pathlib import Path
import hashlib,json,urllib.request as U,zipfile,shutil,tempfile,subprocess
HERE=Path(__file__).resolve().parent
RELEASES=[('https://github.com/XTLS/libXray/releases/download/v26.10.31/libxray-android.zip','fb99f04acf379cc2957c1e1d0ce2b3c0005d3f49974a44c09a11242a0562c1ca','xray'),('https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.3/hev-socks5-tunnel.aar','ab6c581e6157680cfe5687ee7bbdcf95405c006c2db3701879ec44b9e5f83836','hev')]
expected_manifest=json.loads((HERE/"native-sha256.json").read_text()) if (HERE/"native-sha256.json").exists() else None
manifest={}
with tempfile.TemporaryDirectory(prefix='graphite-xray-native-',dir='/tmp') as tmp:
 root=Path(tmp)
 for url,expected,kind in RELEASES:
  archive=root/(kind+'.download')
  with U.urlopen(url,timeout=120) as response,archive.open('wb') as target:shutil.copyfileobj(response,target)
  assert hashlib.sha256(archive.read_bytes()).hexdigest()==expected,'Official artifact SHA256 mismatch'
  if kind=='xray':
   with zipfile.ZipFile(archive) as z:payload=z.read('libxray-android/libXray.aar')
   aar=root/'libXray.aar';aar.write_bytes(payload)
  else:aar=archive
  with zipfile.ZipFile(aar) as z:
   jar=HERE/'src/main/libs'/('libXray-classes.jar' if kind=='xray' else 'hev-socks5-tunnel-classes.jar');jar.parent.mkdir(parents=True,exist_ok=True);jar.write_bytes(z.read('classes.jar'));manifest[str(jar.relative_to(HERE))]=hashlib.sha256(jar.read_bytes()).hexdigest()
   for member in z.namelist():
    if kind=='xray' and member.startswith('jni/') and member.endswith('.so'):
     target=HERE/'src/main/jniLibs'/member.removeprefix('jni/');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(member));manifest[str(target.relative_to(HERE))]=hashlib.sha256(target.read_bytes()).hexdigest()
 path=HERE/'native-sha256.json'
 if expected_manifest:
  assert all(expected_manifest.get(k)==v for k,v in manifest.items()),'Official Xray/JAR payload changed'
 else:path.write_text(json.dumps(manifest,indent=2)+'\n')
 subprocess.run(['python3',str(HERE/'build_hev.py')],check=True)
 manifest=json.loads(path.read_text())
 if expected_manifest:assert manifest==expected_manifest,'Rebuilt payload changed from recorded manifest'
 alignment={}
 for binary in (HERE/'src/main/jniLibs').glob('*/*.so'):
  output=subprocess.check_output(['readelf','-lW',str(binary)],text=True);values=[l.split()[-1] for l in output.splitlines() if l.lstrip().startswith('LOAD')];assert values and all(int(v,16)>=16384 for v in values);alignment[str(binary.relative_to(HERE))]=values
 (HERE/'elf-alignment.json').write_text(json.dumps(alignment,indent=2)+'\n')
 b=(HERE/'src/main/jniLibs/arm64-v8a/libgojni.so').read_bytes();at=b.index(b'\xff Go buildinf:')
 def raw(i):
  n=0;s=0
  while True:
   c=b[i];i+=1;n|=(c&127)<<s
   if not c&128:break
   s+=7
  return b[i:i+n],i+n
 version,end=raw(at+32);mod,end=raw(end);(HERE/'native-buildinfo.txt').write_text(version.decode()+'\n'+mod[16:-16].decode())
print('PINNED_XRAY_ANDROID_NATIVE_READY: Xray + Android26 Hev, four ABIs, two JARs,16KiB alignment')
