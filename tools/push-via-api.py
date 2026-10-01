#!/usr/bin/env python3
"""在 github.com:443 不可达的环境里推送（本机实测：github.com 超时，api.github.com 正常）。

做法：走 GitHub 的 Git Data API —— 逐个上传 blob -> 建 tree -> 建 commit -> 更新 ref。
等价于一次 git push，而且能做到 **远端 commit 与本地 commit 的 SHA 完全一致**：

  * tree 由相同的 blob SHA + 相同的路径/模式拼出来，天然一致；
  * commit 的 author/committer/date/message 全部照抄本地对象，
    日期统一用 UTC（GitHub 会把带偏移的时间归一化成 UTC，用 +0000 才能对上）。

用法：
    printf '%s' '<你的 PAT>' > ~/.dsh-gh-token && chmod 600 ~/.dsh-gh-token
    python3 tools/push-via-api.py            # 把当前 HEAD 推到 main
    BRANCH=dev python3 tools/push-via-api.py # 推别的分支

为什么不用 git push：本机 ~/.gitconfig 里有 url.*.insteadOf 把 github.com
重写到第三方镜像，git 直推会把 token 交给镜像方。本脚本只与 api.github.com 通信。
"""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OWNER = os.environ.get('OWNER', 'likairui48-pixel')
REPO = os.environ.get('REPO', 'dsh-launcher')
BRANCH = os.environ.get('BRANCH', 'main')
API = 'https://api.github.com'


def token():
    t = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
    if t:
        return t.strip()
    p = os.path.expanduser('~/.dsh-gh-token')
    if os.path.exists(p):
        return open(p).read().strip()
    sys.exit('缺少 token：设置 GITHUB_TOKEN 或写入 ~/.dsh-gh-token（chmod 600）')


TOKEN = token()


def git(*args):
    return subprocess.run(['git'] + list(args), cwd=ROOT, capture_output=True, check=True).stdout


def git_txt(*args):
    return git(*args).decode('utf-8')


def call(method, path, body=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header('Authorization', 'Bearer ' + TOKEN)
    req.add_header('Accept', 'application/vnd.github+json')
    req.add_header('User-Agent', 'dsh-launcher-push')
    data = None
    if body is not None:
        data = json.dumps(body).encode('utf-8')
        req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, data, timeout=120) as resp:
            raw = resp.read().decode('utf-8')
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        print('  !! HTTP %s %s %s' % (e.code, method, path))
        print('     ' + e.read().decode('utf-8', 'replace')[:400])
        raise


def raw_message(rev):
    """从原始 commit 对象里取 message，避免 `git log --format=%B` 多加的那个换行。"""
    obj = git('cat-file', 'commit', rev).decode('utf-8')
    return obj.split('\n\n', 1)[1]


def main():
    head = git_txt('rev-parse', 'HEAD').strip()
    parent = git_txt('rev-parse', 'HEAD~1').strip()
    message = raw_message('HEAD')
    an = git_txt('log', '-1', '--format=%an').strip()
    ae = git_txt('log', '-1', '--format=%ae').strip()
    ad = git_txt('log', '-1', '--format=%aI').strip()

    remote = call('GET', '/repos/%s/%s/git/ref/heads/%s' % (OWNER, REPO, BRANCH))['object']['sha']
    print('远端 %s : %s' % (BRANCH, remote))
    print('本地 HEAD : %s' % head)
    if remote == head:
        print('已经一致，无需推送')
        return 0
    if remote != parent:
        print('!! 远端不在本地父提交上，拒绝覆盖（先 git fetch 对齐）')
        return 2

    base_tree = call('GET', '/repos/%s/%s/git/commits/%s' % (OWNER, REPO, parent))['tree']['sha']
    raw = git('diff', '--name-status', '-z', parent, head)
    parts = [p.decode('utf-8') for p in raw.split(b'\x00') if p]
    changed = []
    i = 0
    while i < len(parts):
        st = parts[i]
        if st and st[0] in ('R', 'C'):
            changed.append((parts[i + 2], st[0]))
            i += 3
        else:
            if i + 1 < len(parts):
                changed.append((parts[i + 1], st[0]))
            i += 2
    print('变更文件 : %d' % len(changed))

    tree = []
    for n, (path, status) in enumerate(changed, 1):
        if status == 'D':
            continue
        blob = git('show', '%s:%s' % (head, path))
        sha = call('POST', '/repos/%s/%s/git/blobs' % (OWNER, REPO),
                   {'content': base64.b64encode(blob).decode('ascii'),
                    'encoding': 'base64'})['sha']
        mode = git_txt('ls-tree', head, '--', path).split()[0]
        tree.append({'path': path, 'mode': mode, 'type': 'blob', 'sha': sha})
        if n % 25 == 0 or n == len(changed):
            print('  已上传 %d/%d' % (n, len(changed)))

    new_tree = call('POST', '/repos/%s/%s/git/trees' % (OWNER, REPO),
                    {'base_tree': base_tree, 'tree': tree})['sha']
    commit = call('POST', '/repos/%s/%s/git/commits' % (OWNER, REPO), {
        'message': message,
        'tree': new_tree,
        'parents': [parent],
        'author': {'name': an, 'email': ae, 'date': ad},
        'committer': {'name': an, 'email': ae, 'date': ad},
    })['sha']
    print('远端 commit: %s' % commit)
    print('SHA 一致   : %s' % ('是 ✓' if commit == head else '否（内容一致但哈希不同，多半是日期偏移没归一）'))

    call('PATCH', '/repos/%s/%s/git/refs/heads/%s' % (OWNER, REPO, BRANCH),
         {'sha': commit, 'force': False})
    now = call('GET', '/repos/%s/%s/git/ref/heads/%s' % (OWNER, REPO, BRANCH))['object']['sha']
    print('推送后 HEAD: %s' % now)
    print('结果: %s' % ('PUSH OK ✓' if now == head else 'PUSH 完成但 SHA 不同：本地可 git fetch && git reset --hard origin/%s 对齐' % BRANCH))
    return 0


if __name__ == '__main__':
    sys.exit(main())
