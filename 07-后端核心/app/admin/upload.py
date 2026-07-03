"""
MBclaw 文件上传中转站 — 跑在 47.83.2.188 上
访问 http://47.83.2.188/upload/ 即可

简洁 UI: 拖拽 / 选择 / 粘贴 三种方式
后端: 单密码鉴权 (URL token), 直接落盘到 /var/lib/mbclaw/uploads/
作者拉文件: ssh + scp 即可
"""
import os
import time
import secrets
from pathlib import Path
from fastapi import APIRouter, UploadFile, File, Form, HTTPException, Query
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse
from typing import List

router = APIRouter(prefix="/upload", tags=["upload"])

UPLOAD_DIR = Path(os.environ.get("MBCLAW_UPLOADS", "/var/lib/mbclaw/uploads"))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

# 简单 token (写死, URL 自带; 修改请改后重启)
TOKEN = os.environ.get("MBCLAW_UPLOAD_TOKEN", "mengbai")
MAX_SIZE_MB = 200

def _check_token(t: str):
    if t != TOKEN:
        raise HTTPException(401, "token 错误")

def _safe_upload_path(path: str = "") -> Path:
    safe = "".join(c for c in path.replace("\\", "/").strip("/") if c.isalnum() or c in "._-/")[:128]
    base = (UPLOAD_DIR / safe).resolve() if safe else UPLOAD_DIR.resolve()
    try:
        base.relative_to(UPLOAD_DIR.resolve())
    except ValueError:
        raise HTTPException(403, "forbidden")
    return base

@router.get("/", response_class=HTMLResponse)
def page(t: str = Query("")):
    if t != TOKEN:
        return HTMLResponse(LOGIN_HTML, status_code=200)
    return HTMLResponse(UPLOAD_HTML)

@router.post("/api/upload")
async def upload(t: str = Form(""), path: str = Form(""), files: List[UploadFile] = File(...)):
    _check_token(t)
    base = _safe_upload_path(path)
    base.mkdir(parents=True, exist_ok=True)
    subdir = base.relative_to(UPLOAD_DIR.resolve()).as_posix()
    if subdir == ".":
        subdir = ""
    elif subdir:
        subdir += "/"
    saved = []
    for f in files:
        # 安全文件名: 时间戳 + 原名最后 60 字符
        ts = int(time.time() * 1000)
        safe = "".join(c for c in (f.filename or "file") if c.isalnum() or c in "._-")[-60:]
        if not safe:
            safe = "file"
        name = f"{ts}_{safe}"
        dest = UPLOAD_DIR / subdir / name
        size = 0
        with open(dest, "wb") as out:
            while chunk := await f.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_SIZE_MB * 1024 * 1024:
                    out.close()
                    dest.unlink(missing_ok=True)
                    raise HTTPException(413, f"{f.filename} 超过 {MAX_SIZE_MB}MB")
                out.write(chunk)
        saved.append({
            "name": name,
            "original": f.filename,
            "size": size,
            "url": f"/upload/files/{subdir}{name}",
        })
    return {"ok": True, "saved": saved}

@router.get("/api/list")
def list_files(t: str = Query(""), path: str = Query(""), type: str = Query(""), recursive: bool = Query(False)):
    _check_token(t)
    base = _safe_upload_path(path)
    if not base.exists():
        return {"files": [], "dirs": [], "path": path.strip("/")}
    keyword = type.lower().strip()
    files = []
    dirs = []
    iterator = base.rglob("*") if recursive else base.iterdir()
    for p in iterator:
        s = p.stat()
        rel = p.resolve().relative_to(UPLOAD_DIR.resolve()).as_posix()
        if p.is_dir():
            if not recursive:
                dirs.append({"name": p.name, "path": rel, "mtime": int(s.st_mtime)})
            continue
        if keyword and keyword not in p.name.lower() and keyword not in rel.lower():
            continue
        files.append({
            "name": p.name,
            "path": rel,
            "size": s.st_size,
            "mtime": int(s.st_mtime),
            "url": f"/upload/files/{rel}",
        })
    files.sort(key=lambda x: x["mtime"], reverse=True)
    dirs.sort(key=lambda x: x["mtime"], reverse=True)
    return {"files": files[:500], "dirs": dirs[:200], "path": path.strip("/"), "total": len(files)}

@router.get("/files/{name:path}")
def get_file(name: str):
    p = UPLOAD_DIR / name
    # 安全检查：防止目录穿越
    try:
        p.resolve().relative_to(UPLOAD_DIR.resolve())
    except ValueError:
        raise HTTPException(403, "forbidden")
    if not p.exists() or not p.is_file():
        raise HTTPException(404, "not found")
    return FileResponse(p)

@router.delete("/api/delete/{name:path}")
def delete_file(name: str, t: str = Query("")):
    _check_token(t)
    p = _safe_upload_path(name)
    if p.exists() and p.is_file():
        p.unlink()
        return {"ok": True}
    raise HTTPException(404, "not found")


LOGIN_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MBclaw 上传</title>
<style>
body{margin:0;font-family:-apple-system,sans-serif;background:#F7FAFE;min-height:100vh;display:grid;place-items:center}
.card{background:#fff;border-radius:20px;padding:32px;max-width:380px;width:90%;box-shadow:0 8px 32px rgba(74,144,226,.12);text-align:center}
h1{margin:0 0 8px;color:#4A90E2}
p{color:#6B7785;font-size:13px;margin:0 0 20px}
form{margin:0}
input{width:100%;padding:12px 14px;border:1px solid #E0E6F0;border-radius:12px;font-size:14px;outline:none;box-sizing:border-box}
input:focus{border-color:#4A90E2}
button{margin-top:12px;width:100%;background:#4A90E2;color:#fff;border:0;padding:12px;border-radius:12px;font-size:14px;cursor:pointer}
</style></head>
<body><div class="card">
<h1>🔒 MBclaw 上传</h1>
<p>请输入访问令牌</p>
<form onsubmit="go(event)">
  <input id="t" type="password" placeholder="令牌">
  <button>进入</button>
</form>
</div>
<script>
function go(e){
  e.preventDefault();
  const t = document.getElementById('t').value.trim();
  if(!t) return;
  location.href = '/upload/?t=' + encodeURIComponent(t);
}
</script>
</body></html>"""

UPLOAD_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MBclaw 上传</title>
<style>
:root{--brand:#4A90E2;--bg:#F7FAFE;--card:#fff;--text:#1A2434;--muted:#6B7785;--border:#E0E6F0;--ok:#34C759;--err:#FF3B30}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;font-family:-apple-system,"SF Pro","HarmonyOS Sans","PingFang SC",sans-serif;background:var(--bg);color:var(--text);min-height:100vh;padding:20px 12px}
.wrap{max-width:720px;margin:0 auto}
h1{font-size:22px;margin:0 0 4px;display:flex;align-items:center;gap:10px}
.lg{width:34px;height:34px;border-radius:10px;background:var(--brand);color:#fff;display:grid;place-items:center;font-weight:700;font-size:18px}
.sub{color:var(--muted);font-size:12px;margin-bottom:18px}
.dz{background:var(--card);border:2px dashed var(--border);border-radius:18px;padding:36px 20px;text-align:center;transition:.2s;cursor:pointer}
.dz.over{border-color:var(--brand);background:#F0F7FF}
.dz .ic{font-size:42px}
.dz p{margin:8px 0 4px;color:var(--text);font-weight:600;font-size:15px}
.dz small{color:var(--muted)}
.dz input{display:none}
.actions{display:flex;gap:8px;margin-top:14px;flex-wrap:wrap}
.btn{background:var(--brand);color:#fff;border:0;padding:10px 16px;border-radius:10px;font-size:13px;cursor:pointer;font-weight:500}
.btn.sec{background:var(--card);color:var(--text);border:1px solid var(--border)}
.list{margin-top:24px}
.list h2{font-size:14px;color:var(--muted);font-weight:500;margin:0 0 8px}
.item{background:var(--card);border-radius:14px;padding:12px 14px;display:flex;align-items:center;gap:12px;margin-bottom:8px}
.item .nm{flex:1;min-width:0}
.item .nm b{display:block;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.item .nm small{color:var(--muted);font-size:11px}
.item a,.item button{background:var(--bg);color:var(--text);border:0;padding:6px 10px;border-radius:8px;font-size:11px;cursor:pointer;text-decoration:none}
.item button.del{color:var(--err)}
.toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:var(--text);color:#fff;padding:10px 18px;border-radius:10px;font-size:13px;opacity:0;transition:.2s;pointer-events:none}
.toast.show{opacity:1}
.progress{height:3px;background:var(--border);border-radius:3px;margin-top:8px;overflow:hidden;display:none}
.progress.show{display:block}
.progress .bar{height:100%;background:var(--brand);width:0;transition:.2s}
.empty{color:var(--muted);text-align:center;padding:30px 0;font-size:13px}
.tip{background:#E9F1FB;color:#1A4D8A;padding:10px 12px;border-radius:10px;font-size:12px;margin-top:14px;line-height:1.5}
.tip code{background:#fff;padding:1px 6px;border-radius:4px;font-family:"SF Mono",Menlo,monospace}
</style></head>
<body>
<div class="wrap">
  <h1><span class="lg">M</span> MBclaw 上传中转站</h1>
  <div class="sub">文件直接传给 Claude 工作区 · 单文件 ≤ 200MB</div>

  <label class="dz" id="dz">
    <input type="file" id="picker" multiple>
    <div class="ic">📦</div>
    <p>点击选择 / 拖入文件 / Ctrl+V 粘贴图片</p>
    <small>支持任意格式</small>
  </label>
  <div class="progress" id="prog"><div class="bar" id="bar"></div></div>

  <div class="tip">
    💡 上传后告诉 Claude：「文件已传，叫 <code>xxx.xx</code>」<br>
    Claude 会在 <code>/var/lib/mbclaw/uploads/</code> 读取
  </div>

  <div class="list">
    <h2 id="listTitle">最近上传 (0)</h2>
    <div id="files"></div>
  </div>
</div>
<div class="toast" id="toast"></div>
<script>
const T = new URLSearchParams(location.search).get('t');
const $ = (id) => document.getElementById(id);
function toast(m){const t=$('toast');t.textContent=m;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),1800)}
function fmt(n){if(n<1024)return n+'B';if(n<1048576)return (n/1024).toFixed(1)+'KB';if(n<1073741824)return (n/1048576).toFixed(1)+'MB';return (n/1073741824).toFixed(2)+'GB'}
function fmtT(t){const d=new Date(t*1000),n=Date.now()/1000,diff=n-t;if(diff<60)return '刚刚';if(diff<3600)return Math.floor(diff/60)+'分钟前';if(diff<86400)return Math.floor(diff/3600)+'小时前';return d.toLocaleDateString()+' '+d.toTimeString().slice(0,5)}

async function loadList(){
  try{
    const r = await fetch('/upload/api/list?t='+encodeURIComponent(T));
    const d = await r.json();
    const arr = d.files || [];
    $('listTitle').textContent = `最近上传 (${arr.length})`;
    if(arr.length === 0){
      $('files').innerHTML = '<div class="empty">空空如也，上传点东西吧</div>';
      return;
    }
    $('files').innerHTML = arr.map(f => `
      <div class="item">
        <div class="nm">
          <b>${f.name}</b>
          <small>${fmt(f.size)} · ${fmtT(f.mtime)}</small>
        </div>
        <a href="${f.url}" target="_blank">查看</a>
        <button class="del" onclick="del('${f.name}')">删除</button>
      </div>
    `).join('');
  }catch(e){toast('读取失败')}
}
async function del(name){
  if(!confirm('删除 '+name+' ?')) return;
  await fetch('/upload/api/delete/'+encodeURIComponent(name)+'?t='+encodeURIComponent(T), {method:'DELETE'});
  toast('已删除');
  loadList();
}
async function upload(files){
  if(!files || files.length === 0) return;
  const fd = new FormData();
  fd.append('t', T);
  for(const f of files) fd.append('files', f);
  $('prog').classList.add('show');
  $('bar').style.width = '0%';
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload/api/upload');
  xhr.upload.onprogress = (e) => {
    if(e.lengthComputable){
      $('bar').style.width = (e.loaded/e.total*100) + '%';
    }
  };
  xhr.onload = () => {
    $('prog').classList.remove('show');
    try{
      const d = JSON.parse(xhr.responseText);
      if(d.ok){
        toast(`✅ 已上传 ${d.saved.length} 个`);
        loadList();
      } else {
        toast('❌ ' + (d.detail || '失败'));
      }
    } catch { toast('❌ 网络错误'); }
  };
  xhr.onerror = () => {
    $('prog').classList.remove('show');
    toast('❌ 上传失败');
  };
  xhr.send(fd);
}

$('picker').addEventListener('change', e => upload(e.target.files));
const dz = $('dz');
['dragenter','dragover'].forEach(ev => dz.addEventListener(ev, e => {e.preventDefault();dz.classList.add('over')}));
['dragleave','drop'].forEach(ev => dz.addEventListener(ev, e => {e.preventDefault();dz.classList.remove('over')}));
dz.addEventListener('drop', e => upload(e.dataTransfer.files));

// 粘贴图片
document.addEventListener('paste', e => {
  const items = e.clipboardData?.items;
  if(!items) return;
  const files = [];
  for(const it of items){
    if(it.kind === 'file'){
      const f = it.getAsFile();
      if(f) files.push(f);
    }
  }
  if(files.length > 0) upload(files);
});

loadList();
setInterval(loadList, 8000);
</script>
</body></html>"""
