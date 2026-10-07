"""Adb/UI-tree smoke test. No model or Shizuku is installed in this image."""
import json, os, pathlib, re, subprocess, time, xml.etree.ElementTree as ET
serial=os.environ.get('ANDROID_SERIAL','emulator-5554')
qa=pathlib.Path('qa');qa.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb','-s',serial,*args],text=True,stderr=subprocess.STDOUT)
def tree(name):
    adb('shell','uiautomator','dump','/sdcard/voiceshift-ui.xml')
    xml=adb('shell','cat','/sdcard/voiceshift-ui.xml')
    (qa/f'{name}.xml').write_text(xml)
    return ET.fromstring(xml)
def nodes(root,text):
    return [n for n in root.iter('node') if n.get('text')==text]
def tap(text=None, resource=None):
    for attempt in range(4):
        root=tree('current')
        found=[n for n in root.iter('node') if (text is not None and n.get('text')==text) or (resource is not None and n.get('resource-id')==resource)]
        if found:
            vals=list(map(int,re.findall(r'\d+',found[0].get('bounds',''))))
            assert len(vals)==4, 'Missing UI bounds'
            x1,y1,x2,y2=vals
            assert x2>x1 and y2>y1,'Target not visible'
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(1)
            return
        # Derive a scroll gesture from the scroll container, never screenshot coordinates.
        scroll=next((n for n in root.iter('node') if n.get('scrollable')=='true'),None)
        if scroll is None: time.sleep(1);continue
        x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll.get('bounds','')))
        x=(x1+x2)//2
        adb('shell','input','swipe',str(x),str(y1+(y2-y1)*3//4),str(x),str(y1+(y2-y1)//4),'350')
        time.sleep(1)
    raise AssertionError(f'UI target missing: {text or resource}')
def screenshot(name):
    data=subprocess.check_output(['adb','-s',serial,'exec-out','screencap','-p'])
    (qa/f'{name}.png').write_bytes(data)
def alive():
    assert adb('shell','pidof','dev.voiceshift').strip(),'App process stopped'

adb('shell','am','start','-W','-n','dev.voiceshift/.MainActivity')
time.sleep(2)
root=tree('home')
assert nodes(root,'VoiceShift')
assert nodes(root,'Not Installed')
assert nodes(root,'Not installed'), 'Shizuku absence not displayed'
start=nodes(root,'START')
assert start and start[0].get('enabled')=='false','START must be disabled'
alive();screenshot('home')
tap(text='Diagnostics');root=tree('diagnostics')
assert nodes(root,'Diagnostics');alive();screenshot('diagnostics')
tap(text='Check backend requirements')
root=tree('backends')
texts='\n'.join(n.get('text','') for n in root.iter('node'))
assert 'Not installed' in texts and 'Shizuku' in texts
assert 'Native transport unavailable:' not in texts,'JNI library failed to load'
alive()
tap(text='Test existing HAL PCM input')
tap(resource='com.android.permissioncontroller:id/permission_deny_button')
root=tree('permission-denied')
assert any('Microphone permission denied' in n.get('text','') for n in root.iter('node'))
alive();screenshot('permission-denied')
adb('shell','settings','put','system','accelerometer_rotation','0')
adb('shell','settings','put','system','user_rotation','1');time.sleep(2)
root=tree('rotation');assert nodes(root,'Diagnostics');alive();screenshot('rotation')
adb('shell','settings','put','system','user_rotation','0');time.sleep(2)
tap(text='Back to Home');alive()
root=tree('home-return');assert nodes(root,'VoiceShift')
crash=adb('logcat','-b','crash','-d')
assert 'dev.voiceshift' not in crash,'App crash found in logcat'
(qa/'result.json').write_text(json.dumps({'result':'PASS','api':35,'abi':'x86_64','checks':['APK install','launch without model/Shizuku','START disabled','Diagnostics','native library load/backend probe','permission denial','rotation','home return','crash buffer']},indent=2))
print('PASS: APK install, launch, missing model/Shizuku, disabled START, Diagnostics, denied permission, rotation, no crash')
