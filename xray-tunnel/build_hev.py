"""Build pinned upstream Hev for Android26 (official AAR targets29, native TLS).
NDK r27d archive digest is published by Google's repository2-1.xml.
All toolchain/source extraction stays in /tmp; output has four ABIs and16KiB LOADs.
"""
from pathlib import Path
import hashlib,json,os,shutil,stat,subprocess,tarfile,urllib.request,zipfile
HERE=Path(__file__).resolve().parent
NDK_URL='https://dl.google.com/android/repository/android-ndk-r27d-linux.zip'
NDK_SHA1='22105e410cf29afcf163760cc95522b9fb981121'
SOURCE_SHA256='8aaa33b344cab03a5cfc3675b9aaae55c6ee6088d78678ad1fbb44855b5ba570'
root=Path('/tmp/graphite-hev-android26');root.mkdir(exist_ok=True)
archive=root/'ndk.zip'
if not archive.exists():
 with urllib.request.urlopen(NDK_URL,timeout=180) as response,archive.open('wb') as target:shutil.copyfileobj(response,target)
assert hashlib.sha1(archive.read_bytes()).hexdigest()==NDK_SHA1,'NDK official SHA1 mismatch'
ndk=root/'android-ndk-r27d'
if not (ndk/'ndk-build').exists():
 with zipfile.ZipFile(archive) as z:
  z.extractall(root)
  for member in z.infolist():
   mode=member.external_attr>>16
   if mode&0o111:(root/member.filename).chmod(mode&0o777)
# zipfile extracts symlinks as text; restore the official NDK link targets.
with zipfile.ZipFile(archive) as z:
 for member in z.infolist():
  if stat.S_ISLNK(member.external_attr>>16):
   link=root/member.filename
   if link.exists() or link.is_symlink():link.unlink()
   link.symlink_to(z.read(member).decode())
source=HERE/'upstream/Hev-2.19.3-full-source.tar.xz' 
assert hashlib.sha256(source.read_bytes()).hexdigest()==SOURCE_SHA256
with tarfile.open(source) as z:z.extractall(root,filter='data')
work=root/'hev-socks5-tunnel-2.19.3'
subprocess.run([str(ndk/'ndk-build'),'-C',str(work),'NDK_PROJECT_PATH=.','APP_BUILD_SCRIPT=Android.mk','NDK_APPLICATION_MK=Application.mk','APP_PLATFORM=android-26','-j4'],check=True)
manifest=json.loads((HERE/'native-sha256.json').read_text());alignment={}
for abi in ['armeabi-v7a','arm64-v8a','x86','x86_64']:
 target=HERE/'src/main/jniLibs'/abi/'libhev-socks5-tunnel.so';shutil.copy2(work/'libs'/abi/target.name,target)
 manifest[str(target.relative_to(HERE))]=hashlib.sha256(target.read_bytes()).hexdigest()
for target in (HERE/'src/main/jniLibs').glob('*/*.so'):
 output=subprocess.check_output(['readelf','-lW',str(target)],text=True);values=[l.split()[-1] for l in output.splitlines() if l.lstrip().startswith('LOAD')];assert values and all(int(v,16)>=16384 for v in values);alignment[str(target.relative_to(HERE))]=values
(HERE/'native-sha256.json').write_text(json.dumps(manifest,indent=2)+'\n');(HERE/'elf-alignment.json').write_text(json.dumps(alignment,indent=2)+'\n')
(HERE/'hev-build.json').write_text(json.dumps({'version':'2.19.3','sourceSha256':SOURCE_SHA256,'ndk':'r27d/27.3.13750724','ndkUrl':NDK_URL,'ndkSha1':NDK_SHA1,'ndkSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'targetApi':26,'patches':[],'arguments':['NDK_PROJECT_PATH=.','APP_BUILD_SCRIPT=Android.mk','NDK_APPLICATION_MK=Application.mk','APP_PLATFORM=android-26','-j4']},indent=2)+'\n')
print('HEV_ANDROID26_FOUR_ABIS_BUILT_AND_HASHED',flush=True)
