import os, json, httpx, threading
ALI_KEY = 'sk-06fb2eb8742640d3be0eb9b7743df3c2'
ALI_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
MODELS = ['qwen3.7-max', 'deepseek-v4-pro', 'deepseek-r1', 'kimi-k2.6']

results = {'rounds': 0, 'tokens': 0}
lock = threading.Lock()

def burn(wid):
    client = httpx.Client(timeout=120)
    for i in range(10000):
        model = MODELS[i % len(MODELS)]
        try:
            r = client.post(ALI_URL,
                headers={'Authorization': 'Bearer ' + ALI_KEY, 'Content-Type': 'application/json'},
                json={'model': model, 'messages': [
                    {'role':'system','content':'Write complete Python memory system code. Output only code.'},
                    {'role':'user','content': 'Write MemoryEncoder/Retrieval/Storage. Round ' + str(results['rounds'])}
                ], 'temperature': 0.9, 'max_tokens': 8192})
            if r.status_code == 200:
                data = r.json()
                tk = data.get('usage',{}).get('total_tokens', 0)
                with lock:
                    results['rounds'] += 1
                    results['tokens'] += tk
                print('W' + str(wid) + ' [' + model + '] R' + str(results['rounds']) + ' ' + str(tk) + 'tk')
            elif r.status_code == 429:
                print('W' + str(wid) + ' RATE_LIMIT')
                import time; time.sleep(2)
            else:
                print('W' + str(wid) + ' HTTP' + str(r.status_code) + ':' + r.text[:80])
        except Exception as e:
            print('W' + str(wid) + ' ERR:' + str(e)[:60])

print('8 workers burning Ali quota...')
workers = []
for i in range(8):
    t = threading.Thread(target=burn, args=(i,))
    t.daemon = True
    t.start()
    workers.append(t)

for t in workers:
    t.join()
print('DONE: ' + str(results['rounds']) + ' rounds, ' + str(results['tokens']) + ' tokens')
