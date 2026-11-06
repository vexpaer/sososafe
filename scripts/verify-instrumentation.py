"""Fail closed if Android instrumentation silently executes zero tests."""
import sys
import re
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, ElementTree

raw=Path(sys.argv[1]).read_text(errors='replace')
status={};records=[];continuation=None
for line in raw.splitlines():
    if line.startswith('INSTRUMENTATION_STATUS: '):
        key,sep,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=')
        if sep:status[key]=value;continuation=key
    elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
        code=int(line.split(': ',1)[1])
        if code!=1 and status.get('test'):
            records.append((status.get('class',''),status['test'],code,status.get('stack','')))
        status={};continuation=None
    elif continuation and not line.startswith('INSTRUMENTATION_'):
        status[continuation]+='\n'+line
failures=sum(code!=0 for _,_,code,_ in records)
suite=Element('testsuite',name='sososafe Android device verification',tests=str(len(records)),failures=str(failures),errors='0',skipped='0')
for cls,test,code,stack in records:
    case=SubElement(suite,'testcase',classname=cls,name=test)
    if code!=0:SubElement(case,'failure',message=f'Instrumentation status {code}').text=stack
ElementTree(suite).write(sys.argv[2],encoding='utf-8',xml_declaration=True)
print(f'Executed {len(records)} device tests; failures/skips={failures}')
if len(records)<7 or failures or not re.search(r'^OK \([1-9][0-9]* tests?\)',raw,re.M):
    raise SystemExit('Device verification incomplete: expected at least 7 actual passing tests and JUnit OK result')
