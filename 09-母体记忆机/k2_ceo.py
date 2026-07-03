import httpx, subprocess, os, re, time, shutil

MIMO_KEY = 'tp-s6rzaqvs5q5rbxg05r8cohcf22hzhdsjonzmmunx3u0bveql'
MIMO_URL = 'https://token-plan-sgp.xiaomimimo.com/v1/chat/completions'
QWEN_KEY = 'sk-06fb2eb8742640d3be0eb9b7743df3c2'
QWEN_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
WD = '/opt/mbclaw'
BASELINE = 55

# 只改这5个文件, models.py/db.py锁死不碰
CTX = """MBclaw Phase1程序员. 新表(phase1_models.py/phase1_db.py)已建好:
- raw_memories: id/ws_id/role/content/type/reasoning/parent_id/created_at
- memory_nodes: id/ws_id/layer/content_json/summary/importance/confidence/quality_score/usage_count
- raw_memories_fts: FTS5影子表, 触发器自动同步
- phase1_db.write_raw(db,ws_id,role,content): 写入raw
- phase1_db.search_fts(query,ws_id,limit): FTS5检索
规则: 不碰models.py/db.py/main.py. 不改旧端点. 只输出代码."""

# K2只改这些文件(不碰models.py/db.py)
TASKS = [
    ("app/encoder.py","确保MemoryEncoder.encode存在. 加L1规则引擎函数l1_judge(content)->(layer,importance). 输出代码."),
    ("app/memory.py","在文件末尾加write_raw_memory和search_phase1. write调phase1_db.write_raw. search调phase1_db.search_fts+failure提权1.5x. 旧代码不动. 输出代码."),
    ("app/retrieval.py","加fts5_search函数调phase1_db.search_fts. 加failure_boost(1.5x). 输出代码."),
    ("app/context/builder.py","ContextBuilder.build调search_phase1. score=rel*0.6+imp*0.3+rec*0.1. failure relev>=0.7强拦. <=800字符. 输出代码."),
    ("app/api.py","加POST /memory/search和GET /memory/failures两个端点. 不删旧端点. 输出代码."),
]
ti=[0]

def test():
    r=subprocess.run(['python3','-m','pytest','tests/','-q','--ignore=tests/unit/test_tools.py'],
        cwd=WD,capture_output=True,text=True,timeout=120)
    o=r.stdout+r.stderr
    pm=re.search(r'(\d+) passed',o); fm=re.search(r'(\d+) failed',o)
    return (int(pm.group(1)) if pm else 0),(int(fm.group(1)) if fm else 0),o[-200:]

print("K2 Phase1: models.py/db.py锁死. 只改5文件.",flush=True)
rnd=0; tk=0; a=0; rj=0

while True:
    rnd+=1; fn,task=TASKS[(rnd-1)%5]
    fp=os.path.join(WD,fn)
    cur=open(fp).read()[:2000] if os.path.exists(fp) else ''
    
    try:
        r=httpx.post(MIMO_URL,headers={'Authorization':'Bearer '+MIMO_KEY,'Content-Type':'application/json'},
            json={'model':'mimo-v2.5-pro','messages':[{'role':'user','content':CTX+'\n当前 '+fn+':\n```\n'+cur+'\n```\n任务:'+task}],
                  'max_completion_tokens':16384,'thinking':{'type':'disabled'}},timeout=180)
        if r.status_code!=200: time.sleep(5); continue
        code=r.json()['choices'][0].get('message',{}).get('content','') or ''
        tk+=r.json().get('usage',{}).get('total_tokens',0)
    except: time.sleep(10); continue
    
    for m in ['```python','```py','```']: code=code.replace(m,'')
    code=code.strip()
    if len(code)<200: continue
    if fn.endswith('encoder.py') and any(w in code[:500].lower() for w in ['cv2','numpy','pil','image']):
        print('R%d WRONG DOMAIN'%rnd,flush=True); continue
    
    tmp=fp+'.k2tmp'
    with open(tmp,'w') as f: f.write(code)
    p,f,_=test()
    if f>0 or p<BASELINE:
        rj+=1; os.remove(tmp) if os.path.exists(tmp) else None
        print('R%d [%s] %dc TEST %dP/%dF'%(rnd,fn,len(code),p,f),flush=True); continue
    
    # Qwen审
    try:
        jr=httpx.post(QWEN_URL,headers={'Authorization':'Bearer '+QWEN_KEY,'Content-Type':'application/json'},
            json={'model':'qwen3.7-max','messages':[{'role':'user','content':'Review. Score 0-1. Check: no deleted old code? no broken imports? follows Phase1 rules?\nSCORE:X.XX\n```\n'+code[:8000]+'\n```'}],
                  'max_tokens':512},timeout=60)
        score=0.5
        if jr.status_code==200:
            for l in jr.json()['choices'][0].get('message',{}).get('content','').split('\n'):
                if 'SCORE:' in l:
                    try: score=float(l.split(':')[1].strip()[:4])
                    except: pass
    except: score=0.5
    
    if score<0.7:
        rj+=1; os.remove(tmp) if os.path.exists(tmp) else None
        print('R%d [%s] %dc QWEN=%.2f ❌'%(rnd,fn,len(code),score),flush=True); continue
    
    bak=fp+'.bak'
    if os.path.exists(fp): shutil.copy2(fp,bak)
    shutil.move(tmp,fp)
    p2,f2,_=test()
    if f2>0 or p2<BASELINE:
        if os.path.exists(bak): shutil.move(bak,fp)
        rj+=1
        print('R%d [%s] %dc QWEN=%.2f ROLLBACK %dP/%dF'%(rnd,fn,len(code),score,p2,f2),flush=True)
        os.remove(bak) if os.path.exists(bak) else None; continue
    
    a+=1
    print('R%d [%s] %dc QWEN=%.2f ✅APPLIED %d/%d %dM'%(rnd,fn,len(code),score,a,rnd,tk//1000000),flush=True)
    os.remove(bak) if os.path.exists(bak) else None
    time.sleep(1)
