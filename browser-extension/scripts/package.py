from pathlib import Path
import hashlib,json,re,zipfile

project=Path(__file__).resolve().parents[1]
output=project.parent
version=json.loads((project/'manifest.json').read_text(encoding='utf-8'))['version']
blocked={'.git','node_modules','.local','__pycache__','profile'}
def public(path):
    relative=path.relative_to(project)
    return not any(part in blocked for part in relative.parts) and not path.name.startswith('.env') and path.suffix not in ('.log','.zip','.pem','.p12','.jks','.keystore','.pyc')
def runtime(path):
    relative=path.relative_to(project)
    return relative.parts[0] in ('src','icons','docs') or path.name in ('manifest.json','usage-statistics.json','background.js','content.js','panel.html','panel.css','README.md','PRIVACY.md','CHANGELOG.md','验证记录.md')
archives=[]
for source in (False,True):
    archive=output/('SuiKan-Browser-'+version+('-source' if source else '')+'.zip')
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(project.rglob('*')):
            if path.is_file() and public(path) and (source or runtime(path)):
                data=path.read_bytes()
                for pattern in (rb'sk-[A-Za-z0-9_-]{24,}',rb'AKID[A-Za-z0-9]{16,}',rb'(?:SESSDATA|bili_jct)=[A-Za-z0-9%_\-]{10,}'):
                    assert not re.search(pattern,data),'Private credential pattern in '+str(path.relative_to(project))
                zipped.writestr('suikan-browser/'+path.relative_to(project).as_posix(),data)
    archives.append(archive)
with zipfile.ZipFile(archives[0]) as zipped:
    manifest=json.loads(zipped.read('suikan-browser/manifest.json'))
    assert manifest['manifest_version']==3
    for name in [manifest['background']['service_worker'],manifest['side_panel']['default_path'],*manifest['icons'].values(),'src/panel.js']:
        assert 'suikan-browser/'+name in zipped.namelist(),name
    assert not any('/tests/' in name for name in zipped.namelist())
checks=output/('SHA256SUMS-browser-'+version+'.txt')
checks.write_text(''.join(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name+'\n' for path in archives),encoding='utf-8')
print(json.dumps({'version':version,'private_scan':'PASS','files':[{'name':p.name,'bytes':p.stat().st_size} for p in archives]}))
