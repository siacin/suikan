from pathlib import Path
import argparse,importlib.util,json,os,ssl,threading,uuid
from datetime import datetime,timedelta,timezone
from socketserver import ThreadingMixIn
from wsgiref.simple_server import WSGIServer,make_server
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import ipaddress

root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description='Local-only TLS fixture; requires test dependency cryptography.')
parser.add_argument('--directory',required=True,help='A new private test directory; never use a production database directory.')
args=parser.parse_args()
spec=importlib.util.spec_from_file_location('suikan_statistics',root/'statistics/server.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
work=Path(args.directory).resolve();work.mkdir(parents=True,exist_ok=True)
if (work/'android.sqlite3').exists() or (work/'dashboard.sqlite3').exists():raise SystemExit('Use a new fixture directory; existing databases are not deleted.')
assets=work/'assets';assets.mkdir(exist_ok=True)
token='local-test-statistics-'+'x'*32
key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'localhost')])
now=datetime.now(timezone.utc)
cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
    .not_valid_before(now-timedelta(days=1)).not_valid_after(now+timedelta(days=365))
    .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'),x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),critical=False)
    .add_extension(x509.BasicConstraints(ca=True,path_length=None),critical=True).sign(key,hashes.SHA256()))
(assets/'usage-test-ca.crt').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
(work/'fixture-key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
class Threaded(ThreadingMixIn,WSGIServer):daemon_threads=True
apps=[]
for port,filename,secure in [(18767,'dashboard.sqlite3',False),(18768,'android.sqlite3',True)]:
    database=work/filename
    app=m.UsageApplication(database,token);apps.append(app)
    if not secure:
        today=app.now()
        for i in range(184):
            days=[(today-timedelta(days=j)).isoformat() for j in range(30) if (i*7+j*3)%11<5 and (i+j)%7!=0]
            operations=[{'day':day,'extract':[i%4,i%4,0,0],'summary':[i%3+1,i%3,1,0]} for day in days]
            app.database.record(str(uuid.uuid4()),'0.1.11' if i%3 else '0.1.10',days,today,operations)
    server=make_server('127.0.0.1',port,app,server_class=Threaded,handler_class=m.QuietHandler)
    if secure:
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(str(assets/'usage-test-ca.crt'),str(work/'fixture-key.pem'));server.socket=ctx.wrap_socket(server.socket,server_side=True)
    threading.Thread(target=server.serve_forever,daemon=True).start()
(work/'state.json').write_text(json.dumps({'pid':os.getpid(),'http':18767,'https':18768}),encoding='utf-8')
print('Local-only fixtures: HTTP 18767 / HTTPS 18768; sample data, not real users. TestAssets='+str(assets),flush=True)
threading.Event().wait()
