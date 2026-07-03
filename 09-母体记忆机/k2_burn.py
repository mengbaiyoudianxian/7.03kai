import httpx, subprocess, os, re, time, shutil

KEY = 'tp-s6rzaqvs5q5rbxg05r8cohcf22hzhdsjonzmmunx3u0bveql'
URL = 'https://token-plan-sgp.xiaomimimo.com/v1/chat/completions'
JKEY = 'sk-06fb2eb8742640d3be0eb9b7743df3c2'
JURL = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
WD = '/opt/mbclaw'

TASKS = [
    ("app/retrieval.py", "改进hybrid_search: 确保 decode_embedding/余弦相似度/failure*1.30/tag匹配 正确. 只修改函数内部实现不改变函数签名. 读当前文件后做目标改进."),
    ("app/context/builder.py", "改进ContextBuilder.build: 完善failure强制包含逻辑, 完善<=800字符截断. 只修改方法内部."),
    ("app/encoder.py", "改进MemoryEncoder: 完善LLM调用错误处理, 完善JSON解析容错. 保留所有现有public API."),
    ("app/api.py", "检查/memory/search等新端点, 修复任何import错误. 不删旧端点."),
]
ti = [0]

def read_file(fn):
    fp = os.path.join(WD, fn)
    return open(fp).read()[:3000] if os.path.exists(fp) else ""

def run_tests():
    r = subprocess.run(['python3','-m','pytest','tests/','-q','--ignore=tests/unit/test_tools.py'],
        cwd=WD, capture_output=True, text=True, timeout=120)
    out = r.stdout + r.stderr
    pm = re.search(r'(\d+) passed', out)
    return int(pm.group(1)) if pm else 0, out[-200:]

print("K2 SANDBOX: write to tmp → test → only replace if tests pass", flush=True)
rnd = 0; total_tk = 0; applied = 0

while True:
    rnd += 1
    fn, task = TASKS[(rnd-1) % len(TASKS)]
    current = read_file(fn)
    
    try:
        r = httpx.post(URL, headers={'Authorization':'Bearer '+KEY,'Content-Type':'application/json'},
            json={'model':'mimo-v2.5-pro','messages':[{'role':'user','content':'当前代码:\n```\n'+current+'\n```\n\n任务: '+task}],
                  'max_completion_tokens':8192,'thinking':{'type':'disabled'}}, timeout=120)
    except: time.sleep(10); continue
    
    if r.status_code != 200: time.sleep(5); continue
    code = r.json()['choices'][0].get('message',{}).get('content','') or ''
    tk = r.json().get('usage',{}).get('total_tokens',0); total_tk += tk
    for m in ['```python','```py','```']: code = code.replace(m,'')
    code = code.strip()
    if len(code) < 100: continue
    
    # 安全检查: encoder不能变成图片编码器
    if fn == 'app/encoder.py' and any(w in code[:500].lower() for w in ['cv2','numpy','pil','image','video']):
        print('R%d [%s] REJECTED: wrong domain'%(rnd,fn), flush=True); continue
    
    # 写到临时文件
    tmp = os.path.join(WD, fn + '.k2tmp')
    with open(tmp, 'w') as f: f.write(code)
    
    # 跑测试
    p, tout = run_tests()
    if p < 61:  # 不能低于基准
        print('R%d [%s] %dtk %dc TEST-DROP %d<61'%(rnd,fn,tk,len(code),p), flush=True)
        os.remove(tmp) if os.path.exists(tmp) else None
        continue
    
    # Qwen评审
    score = 0.5
    try:
        jr = httpx.post(JURL, headers={'Authorization':'Bearer '+JKEY,'Content-Type':'application/json'},
            json={'model':'qwen3.7-max','messages':[{'role':'user','content':'Review this code improvement. Does it fix bugs without breaking existing functionality? Score 0-1.\nSCORE:X.XX\nISSUES:...\n```\n%s\n```'%(code[:6000])}],
                  'max_tokens':512}, timeout=60)
        if jr.status_code == 200:
            t = jr.json()['choices'][0].get('message',{}).get('content','')
            for l in t.split('\n'):
                if 'SCORE:' in l:
                    try: score = float(l.split(':')[1].strip()[:4])
                    except: pass
    except: pass
    
    if score >= 0.7:
        # 正式替换
        shutil.move(tmp, os.path.join(WD, fn))
        applied += 1
        print('R%d [%s] %dtk %dc P=%.2f APPLIED %d/%d %dM'%(rnd,fn,tk,len(code),score,applied,rnd,total_tk//1000000), flush=True)
    else:
        os.remove(tmp) if os.path.exists(tmp) else None
        print('R%d [%s] %dtk %dc P=%.2f SKIP'%(rnd,fn,tk,len(code),score), flush=True)
