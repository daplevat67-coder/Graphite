"""Package source and evidence, excluding private signing material and build caches."""
from pathlib import Path
import zipfile
source=Path(__file__).resolve().parents[1]
output=source.parent/'output';output.mkdir(exist_ok=True)
blocked={'build','.gradle','.kotlin','.native-cache','.idea','.git','dist'}
with zipfile.ZipFile(output/'GRAPHITE_VPN_V0.2.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
 for path in sorted(source.rglob('*')):
  rel=path.relative_to(source)
  if not path.is_file() or any(part in blocked for part in rel.parts):continue
  if path.name in ('signing.properties','local.properties') or path.suffix.lower() in ('.p12','.jks','.keystore','.gwp'):continue
  archive.write(path,Path('GRAPHITE_VPN')/rel)
 print('Source files:',len(archive.namelist()))
