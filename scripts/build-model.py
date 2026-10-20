"""Convert the pinned, unmodified official binary STL files to an offline JS asset."""
from pathlib import Path
import base64
import hashlib
import json
import struct

root = Path(__file__).resolve().parents[1]
source = root / 'docs/model-source'
meshes = []
for name in ['base_link'] + [f'link_{i}' for i in range(1, 7)]:
    raw = (source / f'{name}.STL').read_bytes()
    count = struct.unpack_from('<I', raw, 80)[0]
    assert len(raw) == 84 + count * 50
    positions = b''.join(raw[84+i*50+12:84+i*50+48] for i in range(count))
    meshes.append(dict(name=name, triangles=count, source_sha256=hashlib.sha256(raw).hexdigest(),
                       positions=base64.b64encode(positions).decode()))
data = dict(repository='https://github.com/RealManRobot/rm_models',
            commit='9d37f52605aa00c7b0ed8143e5e46b8ffdb9317a',
            path='RM65/urdf/RM65-6F', meshes=meshes)
target = root / 'app/src/main/assets/rm65-model.js'
target.write_text('// Official RealMan RM65-6F meshes; Apache-2.0. See vendor/RM65-MODEL-LICENSE.txt.\n'
                  '// Converted from STL by scripts/build-model.py; coordinates in metres.\n'
                  'window.RM65MeshData = ' + json.dumps(data, separators=(',', ':')) + ';\n')
print(f'{sum(m["triangles"] for m in meshes)} triangles; {target.stat().st_size} bytes')
