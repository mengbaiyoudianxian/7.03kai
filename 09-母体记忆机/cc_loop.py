import subprocess, os, httpx, re, shutil, time

WD = '/opt/mbclaw'
QWEN_KEY = 'sk-06fb2eb8742640d3be0eb9b7743df3c2'  # dead
QWEN_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'  # dead

FILES = [
    ("app/encoder.py","Verify MemoryEncoder exists. Add l1_judge(content) function returning (layer,importance). Keep existing code."),
    ("app/memory.py","Add write_raw_memory and search_phase1 functions. Use phase1_db. Keep old MemoryRepo."),
    ("app/retrieval.py","Add fts5_search using phase1_db.search_fts. Failure boost 1.5x."),
    ("app/context/builder.py","ContextBuilder.build use search_phase1. Score=rel*0.6+imp*0.3+rec*0.1. Max 800 chars."),
    ("app/api.py","Add POST /memory/search and GET /memory/failures. Keep old endpoints."),
]
ti=[0]

def test():
    r=subprocess.run(['python3','-m','pytest','tests/','-q','--ignore=tests/unit/test_tools.py'],
        cwd=WD,capture_output=True,text=True,timeout=120)
    o=r.stdout+r.stderr
    pm=re.search(r'(\d+) passed',o); fm=re.search(r'(\d+) failed',o)
    return (int(pm.group(1)) if pm else 0),(int(fm.group(1)) if fm else 0)

def call_claude(prompt):
    """用 stdin 传 prompt, 不回显"""
    env = {**os.environ,
        'ANTHROPIC_BASE_URL': 'https://api.deepseek.com/anthropic',
        'ANTHROPIC_AUTH_TOKEN': 'sk-ea0325ce0599441f84969d029be0ca2a',
        'ANTHROPIC_MODEL': 'deepseek-v4-pro[1m]',
        'ANTHROPIC_DEFAULT_OPUS_MODEL': 'deepseek-v4-pro[1m]',
        'ANTHROPIC_DEFAULT_SONNET_MODEL': 'deepseek-v4-pro[1m]',
        'ANTHROPIC_DEFAULT_HAIKU_MODEL': 'deepseek-v4-flash',
    }
    r = subprocess.run(['claude', '--effort', 'high', '-p', prompt],
        cwd=WD, capture_output=True, text=True, timeout=300, env=env)
    return r.stdout.strip()

def call_qwen(code):
    try:
        r=httpx.post(QWEN_URL,headers={'Authorization':'Bearer '+QWEN_KEY,'Content-Type':'application/json'},
            json={'model':'deepseek-v4-pro','messages':[{'role':'user','content':'Review. Score 0-1. Old code preserved?\nSCORE:X.XX\n```\n'+code[:6000]+'\n```'}],
                  'max_tokens':512},timeout=60)
        if r.status_code==200:
            t=r.json()['choices'][0].get('message',{}).get('content','')
            for l in t.split('\n'):
                if 'SCORE:' in l:
                    try: return float(l.split(':')[1].strip()[:4])
                    except: pass
    except: pass
    return 0.5

print("CC v2: Claude(MiMo)+Qwen. stdin prompt.",flush=True)
rnd=0; a=0; rj=0

while True:
    fail_count = {}
    rnd+=1; fn,task=FILES[(rnd-1)%5]
    fp=os.path.join(WD,fn)
    cur=open(fp).read()[:2000] if os.path.exists(fp) else ''
    prompt = "Read "+fn+". Current code:\n```\n"+cur+"\n```\n\nTask: "+task+"\n\nOutput the complete fixed Python file. No explanation. Just code."
    
    code = call_claude(prompt)
    for m in ['```python','```py','```']: code=code.replace(m,'')
    code=code.strip()
    
    if len(code)<200:
        print('R%d [%s] EMPTY(%d)'%(rnd,fn,len(code)),flush=True); time.sleep(5); continue
    if fn.endswith('encoder.py') and any(w in code[:500].lower() for w in ['cv2','numpy','pil','image']):
        print('R%d WRONG'%rnd,flush=True); continue
    
    tmp=fp+'.cctmp'
    with open(tmp,'w') as f: f.write(code)
    p,f=test()
    if f>0 or p<50:
        rj+=1; os.remove(tmp)
        print('R%d [%s] T%d/%d'%(rnd,fn,p,f),flush=True); continue
    
    score=call_qwen(code)
    if score<0.7:
        rj+=1; os.remove(tmp)
        print('R%d [%s] Q%.2f'%(rnd,fn,score),flush=True); continue
    
    bak=fp+'.ccbak'
    if os.path.exists(fp): shutil.copy2(fp,bak)
    shutil.move(tmp,fp)
    p2,f2=test()
    if f2>0 or p2<50:
        if os.path.exists(bak): shutil.move(bak,fp)
        rj+=1
        print('R%d [%s] Q%.2f ROLL %d/%d'%(rnd,fn,score,p2,f2),flush=True)
        os.remove(bak) if os.path.exists(bak) else None; continue
    
    a+=1
    print('R%d [%s] Q%.2f ✅%d/%d'%(rnd,fn,score,a,rnd),flush=True)
    os.remove(bak) if os.path.exists(bak) else None
    time.sleep(2)
