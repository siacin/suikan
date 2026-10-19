import pg from 'pg';
export function createPool(env=process.env){
  const port=Number(env.PGPORT||5432);
  if(!env.PGHOST||!env.PGDATABASE||!env.PGUSER||!env.PGPASSWORD||!Number.isInteger(port)||port<1||port>65535)throw new Error('Database configuration missing');
  const mode=env.PGSSLMODE||'verify-full';
  if(!['verify-full','require','disable'].includes(mode))throw new Error('Invalid database TLS mode');
  const ssl=mode==='disable'?false:{rejectUnauthorized:mode==='verify-full',...(env.PGSSLROOTCERT?{ca:env.PGSSLROOTCERT}:{})};
  const pool=new pg.Pool({host:env.PGHOST,port,database:env.PGDATABASE,user:env.PGUSER,password:env.PGPASSWORD,
    ssl,max:3,idleTimeoutMillis:10000,connectionTimeoutMillis:2000,query_timeout:6000,
    application_name:'suikan-statistics-0.1.12',options:'-c search_path=suikan,pg_catalog'});
  pool.on('error',()=>console.error('Database connection temporarily unavailable'));
  return pool;
}
