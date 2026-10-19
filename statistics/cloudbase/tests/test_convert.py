import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('convert_backup',ROOT/'tools'/'convert-d1-backup.py')
convert_backup=importlib.util.module_from_spec(spec)
spec.loader.exec_module(convert_backup)

class BackupTests(unittest.TestCase):
    def backup(self,directory,operations=True):
        db=sqlite3.connect(':memory:')
        old=ROOT.parent/'cloudflare'/'migrations'
        db.executescript((old/'0001_initial.sql').read_text())
        if operations: db.executescript((old/'0002_operations.sql').read_text())
        db.execute('INSERT INTO installations VALUES(?,?,?,?)',('a'*64,'2026-09-01','2026-10-10','0.1.11'))
        db.execute('INSERT INTO active VALUES(?,?)',('2026-10-10','a'*64))
        db.execute('INSERT INTO installation_access VALUES(?,?,?)',('a'*64,0,'保留状态'))
        if operations:
            db.execute('INSERT INTO operation_daily(day,id,extract_started) VALUES(?,?,?)',('2026-10-10','a'*64,3))
            db.execute('INSERT INTO operation_totals(id,extract_started) VALUES(?,?)',('a'*64,7))
        sql=Path(directory)/'backup.sql'
        sql.write_text('\n'.join(db.iterdump()),encoding='utf-8')
        db.close()
        return sql

    def test_preserves_all_five_tables_and_hashed_identifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            value=convert_backup.convert(self.backup(directory))
            self.assertEqual(value['tables']['operation_totals'][0]['extract_started'],7)
            self.assertEqual(value['tables']['operation_daily'][0]['extract_started'],3)
            self.assertEqual(value['tables']['installation_access'][0]['reason'],'保留状态')
            self.assertEqual(value['tables']['installations'][0]['id'],'a'*64)
            self.assertEqual(set(value['tables']),set(convert_backup.TABLES))

    def test_old_backup_has_no_fabricated_usage(self):
        with tempfile.TemporaryDirectory() as directory:
            value=convert_backup.convert(self.backup(directory,False))
            self.assertEqual(value['tables']['operation_daily'],[])
            self.assertEqual(value['tables']['operation_totals'],[])

    def test_sql_cannot_attach_or_write_an_external_database(self):
        with tempfile.TemporaryDirectory() as directory:
            sql=Path(directory)/'backup.sql';target=Path(directory)/'should-not-exist.db'
            sql.write_text("ATTACH DATABASE '"+target.as_posix()+"' AS external;",encoding='utf-8')
            with self.assertRaises(sqlite3.DatabaseError): convert_backup.convert(sql)
            self.assertFalse(target.exists())

    def test_missing_statistics_tables_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            sql=Path(directory)/'backup.sql';sql.write_text('CREATE TABLE unrelated(id);',encoding='utf-8')
            with self.assertRaises(ValueError): convert_backup.convert(sql)

if __name__=='__main__': unittest.main()
