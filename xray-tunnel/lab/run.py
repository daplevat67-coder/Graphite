"""Disposable Android28/Xray loopback test. Requires graphitelocalfixture-gated instrumentation."""
from pathlib import Path
import subprocess,time,json,os,re,signal,sys
r=Path(__file__).resolve().parents[3]/'toolchain';root=r.parent
sys.stdout=(r/'device-xray.log').open('w',buffering=1);sys.stderr=sys.stdout
avd=Path('/tmp/graphite-xray-avd');d=avd/'graphite-xray-lab.avd';d.mkdir(parents=True,exist_ok=True)
(avd/'graphite-xray-lab.ini').write_text(f'avd.ini.encoding=UTF-8\npath={d}\ntarget=android-28\n')
(d/'config.ini').write_text(f'''avd.ini.encoding=UTF-8
AvdId=graphite-xray-lab
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=720
hw.lcd.height=1600
hw.lcd.density=320
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.mainKeys=no
hw.audioInput=no
hw.audioOutput=no
image.sysdir.1={r}/android-sdk/system-images/android-28/default/x86_64/
tag.id=default
tag.display=Default
disk.dataPartition.size=2G
PlayStore.enabled=false
fastboot.forceColdBoot=yes
''')
env=os.environ.copy();env.update(ANDROID_AVD_HOME=str(avd),ANDROID_HOME=str(r/'android-sdk'),ANDROID_USER_HOME='/tmp/graphite-xray-android-user',ANDROID_I_WANT_MY_TCG='yes',LD_LIBRARY_PATH=str(r/'emulator-libs/usr/lib/x86_64-linux-gnu'))
adb=str(r/'android-sdk/platform-tools/adb');device='emulator-5572'
def call(args,timeout=180):
 p=subprocess.run([adb,'-s',device]+args,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout);print(p.stdout,flush=True)
 if p.returncode:raise RuntimeError('ADB failure: '+str(args[:2]))
 return p.stdout
subprocess.run([adb,'start-server'],env=env,stdin=subprocess.DEVNULL,check=True)
log=(r/'emulator-xray.log').open('w');emu=subprocess.Popen([str(r/'android-sdk/emulator/emulator'),'-avd','graphite-xray-lab','-port','5572','-accel','off','-no-window','-no-audio','-no-metrics','-no-boot-anim','-gpu','swiftshader_indirect','-feature','-Vulkan','-no-snapshot','-wipe-data','-cores','2','-memory','2048','-show-kernel'],env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
def ready(path,marker):
 end=time.monotonic()+90
 while time.monotonic()<end:
  p=subprocess.run([adb,'-s',device,'shell','cat',path],env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=20)
  if marker in p.stdout:print(marker,flush=True);return
  if p.stdout.strip():raise RuntimeError('Fixture startup failed: '+p.stdout)
  time.sleep(2)
 raise RuntimeError('Fixture readiness timeout')
def test(cls):
 out=call(['shell','am','instrument','-w','-e','graphiteLocalFixture','true','-e','class','art.graphite.vpn.'+cls,'art.graphite.vpn.test/androidx.test.runner.AndroidJUnitRunner'],900)
 assert 'OK (' in out and 'FAILURES' not in out and 'AssumptionViolatedException' not in out,'Instrumentation failed'
 return out

def verify_native_sessions(output):
 markers=re.findall(
  r'^GRAPHITE_NATIVE_SESSION protocol=(vless|vmess) cycle=([12]) payload=OK '
  r'rxBytes=([1-9][0-9]*) txBytes=([1-9][0-9]*) stopped=DOWN$', output, re.MULTILINE)
 expected=[('vless','1'),('vless','2'),('vmess','1'),('vmess','2')]
 if [marker[:2] for marker in markers]!=expected:
  raise RuntimeError('Expected four ordered Xray payload sessions with positive RX/TX and clean shutdown')

try:
 print('Waiting for isolated API28 boot',flush=True);end=time.monotonic()+900
 while time.monotonic()<end:
  p=subprocess.run([adb,'-s',device,'shell','getprop','sys.boot_completed'],env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=20)
  if p.stdout.strip()=='1':break
  if emu.poll() is not None:raise RuntimeError('Emulator exited')
  time.sleep(10)
 else:raise RuntimeError('Boot timeout')
 print('BOOTED_XRAY_API28',flush=True)
 fixtures=Path('/tmp/graphite-xray-lab-fixtures');fixtures.mkdir(exist_ok=True);uuid='11111111-1111-4111-8111-111111111111';(fixtures/'graphite-xray-uuid.txt').write_text(uuid+'\n')
 config={'log':{'loglevel':'none'},'inbounds':[{'listen':'127.0.0.1','port':52401,'protocol':'vless','settings':{'clients':[{'id':uuid}],'decryption':'none'}},{'listen':'127.0.0.1','port':52402,'protocol':'vmess','settings':{'clients':[{'id':uuid,'alterId':0}]}}],'outbounds':[{'protocol':'freedom','settings':{'redirect':'127.0.0.1:18080','finalRules':[{'action':'allow','network':'tcp','ip':['127.0.0.1/32'],'port':'18080'}]}}]}
 (fixtures/'graphite-xray-server.json').write_text(json.dumps(config));(fixtures/'graphite-lab.conf').write_text('[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE=\nAddress = 10.199.0.2/32\n[Peer]\nPublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE=\nEndpoint = 127.0.0.1:51880\nAllowedIPs = 10.199.0.0/24\n')
 for binary,name in [(r/'xray-lab-core/xray','graphite-xray-core'),(r/'xray-lab-http','graphite-xray-http')]:call(['push',str(binary),'/data/local/tmp/'+name]);call(['shell','chmod','700','/data/local/tmp/'+name])
 for p in fixtures.iterdir():call(['push',str(p),'/data/local/tmp/'+p.name])
 call(['shell','nohup /data/local/tmp/graphite-xray-http > /data/local/tmp/graphite-xray-http.log 2>&1 &']);ready('/data/local/tmp/graphite-xray-http.log','GRAPHITE_XRAY_HTTP_READY')
 call(['shell','nohup /data/local/tmp/graphite-xray-core run -config /data/local/tmp/graphite-xray-server.json > /data/local/tmp/graphite-xray-server.log 2>&1 &'])
 out=call(['shell','/data/local/tmp/graphite-xray-core','run','-test','-config','/data/local/tmp/graphite-xray-server.json'],90);assert 'Configuration OK' in out,'Xray configuration rejected'
 print('LOCAL_XRAY_VLESS_VMESS_SERVERS_READY',flush=True);print('Waiting for /tmp/graphite-xray-apk-ready',flush=True)
 while not Path('/tmp/graphite-xray-apk-ready').exists():time.sleep(2)
 old=root/'output/GRAPHITE_VPN_V0.2.7-debug.apk'
 if not old.exists():old=root/'restored/WW/GRAPHITE_VPN_V0.2.7-debug.apk'
 call(['install','--no-streaming',str(old)],300);call(['install','--no-streaming','-t','/tmp/graphite-xray-test.apk'],300);test('UpdateSeedDeviceTest')
 call(['install','--no-streaming','-r','/tmp/graphite-xray-ready.apk'],300);print('XRAY_UPDATED_WITHOUT_UNINSTALL',flush=True);call(['shell','appops','set','art.graphite.vpn','ACTIVATE_VPN','allow']);call(['shell','cmd','package','compile','-m','speed','-f','art.graphite.vpn'],600);call(['shell','cmd','package','compile','-m','speed','-f','art.graphite.vpn.test'],600);print('XRAY_EMULATOR_AOT_PREPARED',flush=True);verify_native_sessions(test('XrayNativeLabDeviceTest'));print('XRAY_NATIVE_VLESS_VMESS_PAYLOAD_RECONNECT_UPGRADE_PASSED',flush=True)
except Exception as e:
 print(type(e).__name__+': '+str(e),flush=True);print('XRAY_EMULATOR_READY_FOR_INSPECTION',flush=True);deadline=time.monotonic()+1800
 while time.monotonic()<deadline and not (r/'stop-xray-device').exists() and emu.poll() is None:
  request=r/'xray-adb-request.json'
  if request.exists():
   q=json.loads(request.read_text());request.unlink();deadline=time.monotonic()+1800
   try:
    p=subprocess.run([adb,'-s',device]+q['argv'],env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=q.get('timeout',180));(r/('xray-adb-response-'+q['id']+'.log')).write_text(p.stdout)
   except Exception as failure:(r/('xray-adb-response-'+q['id']+'.log')).write_text(str(failure))
  time.sleep(2)
 raise
finally:
 try:os.killpg(emu.pid,signal.SIGTERM)
 except ProcessLookupError:pass
 try:emu.wait(timeout=15)
 except subprocess.TimeoutExpired:emu.kill()
