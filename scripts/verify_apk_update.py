"""Verify package, signatures and monotonically increasing versionCode. No device install."""
import argparse,json,os,pathlib,re,subprocess
p=argparse.ArgumentParser();p.add_argument('previous');p.add_argument('candidate');p.add_argument('--build-tools',required=True);args=p.parse_args()
tools=pathlib.Path(args.build_tools)
def metadata(apk):
 subprocess.run([str(tools/'apksigner'),'verify',apk],check=True,capture_output=True)
 cert=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',apk],text=True)
 fingerprints=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',cert)
 badging=subprocess.check_output([str(tools/'aapt'),'dump','badging',apk],text=True)
 match=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
 assert match and fingerprints,'Invalid APK metadata'
 return {'package':match[1],'versionCode':int(match[2]),'versionName':match[3],'signersSHA256':sorted(fingerprints)}
a,b=metadata(args.previous),metadata(args.candidate)
assert a['package']==b['package'],'Different applicationId'
assert a['signersSHA256']==b['signersSHA256'],'Different signing certificate'
assert b['versionCode']>a['versionCode'],'versionCode did not increase'
print(json.dumps({'previous':a,'candidate':b,'updateMetadataCompatible':True,'deviceInstallationTested':False},indent=2))
