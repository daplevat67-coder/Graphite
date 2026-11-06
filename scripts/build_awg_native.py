from pathlib import Path
import os,subprocess,concurrent.futures,hashlib,json
project=Path(__file__).resolve().parent.parent
ndk=Path(os.environ['ANDROID_NDK_HOME']);go=Path(os.environ['GRAPHITE_AWG_GO']);cache=Path(os.environ.get('GRAPHITE_NATIVE_CACHE','/tmp/graphite-native-cache'));cache.mkdir(exist_ok=True)
clang=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
abis=[('arm64-v8a','arm64','aarch64-linux-android26',''),('x86_64','amd64','x86_64-linux-android26',''),('armeabi-v7a','arm','armv7a-linux-androideabi26','7'),('x86','386','i686-linux-android26','')]
def build(item):
 abi,arch,target,arm=item;out=project/'awg-tunnel/src/main/jniLibs'/abi;out.mkdir(parents=True,exist_ok=True)
 env=os.environ.copy();env.update(GOOS='android',GOARCH=arch,CGO_ENABLED='1',CC=str(clang/(target+'-clang')),GOMAXPROCS='2',GOCACHE=str(cache/'gocache'),GOPATH=str(cache/'gopath'),GOTOOLCHAIN='local',CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,-soname,libwg-go.so')
 if arm:env['GOARM']=arm
 with (cache/(abi+'.log')).open('w') as log:subprocess.run([str(go),'build','-tags=linux','-trimpath','-buildvcs=false','-ldflags=-s -w -buildid= -X github.com/amnezia-vpn/amneziawg-go/v3/ipc.socketDirectory=/data/data/art.graphite.vpn/cache/amneziawg','-buildmode=c-shared','-o',str(out/'libwg-go.so'),'.'],cwd=project/'awg-tunnel/native',env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
 (out/'libwg-go.h').unlink(missing_ok=True);print(abi,'BUILT',flush=True)
 return str(Path('src/main/jniLibs')/abi/'libwg-go.so'),hashlib.sha256((out/'libwg-go.so').read_bytes()).hexdigest()
with concurrent.futures.ThreadPoolExecutor(2) as ex:results=list(ex.map(build,abis))
(project/'awg-tunnel/native-sha256.json').write_text(json.dumps(dict(results),indent=2)+'\n')
