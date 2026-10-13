"""端到端冒烟: 登录 → 创建 AI 运营问答任务 → 轮询结果并打印工具轨迹与答案。

用法: python -I scripts/smoke_agent.py [问题] [--base http://localhost:8080] [--user operator] [--password ...]
"""
import argparse
import http.cookiejar
import json
import time
import urllib.request

p = argparse.ArgumentParser()
p.add_argument("question", nargs="?", default="今天冰雪大世界的入园人数和在园峰值是多少？哪个区域最拥挤？太阳岛最近一周负面评价主要集中在哪些问题？")
p.add_argument("--base", default="http://localhost:8080")
p.add_argument("--user", default="operator")
p.add_argument("--password", default="Snow@2026demo")
p.add_argument("--scenario", default="QA")
p.add_argument("--site", type=int)
args = p.parse_args()

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def xsrf():
    return next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")


def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(args.base + "/api/v1" + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "X-XSRF-TOKEN": xsrf()})
    try:
        with opener.open(req, timeout=60) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code} {e.read().decode('utf-8', 'replace')}")


call("GET", "/auth/csrf")
call("POST", "/auth/login", {"username": args.user, "password": args.password})
call("GET", "/auth/csrf")
info = call("GET", "/system/info")
print("AI:", info["aiProvider"], info["aiModel"])

body = {"scenario": args.scenario}
if args.scenario == "QA":
    body["question"] = args.question
elif args.scenario == "DAILY_REPORT":
    body.update(siteId=args.site or 1, reportDate=info["now"][:10])
elif args.scenario == "MARKETING":
    body.update(goal=args.question)
elif args.scenario == "REVIEW_ANALYSIS":
    body.update(siteId=args.site or 1, limit=10)
run = call("POST", "/agent/runs", body)
rid = run["runId"]
t0 = time.time()
while True:
    d = call("GET", f"/agent/runs/{rid}")
    if d["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
        break
    time.sleep(2)
print(f"{d['status']} in {time.time() - t0:.0f}s  provider={d['provider']} model={d['model']} "
      f"modelCalls={d['modelCalls']} toolCalls={d['toolCalls']} tokens={d['tokensIn']}/{d['tokensOut']}")
if d.get("errorCode"):
    print("ERROR:", d["errorCode"], d.get("errorMessage"))
for s in d.get("steps") or []:
    print(f"  [{s['status']}] {s['toolName']} {json.dumps(s['arguments'], ensure_ascii=False)[:90]} -> {(s.get('resultSummary') or '')[:70]}")
print(json.dumps(d.get("result"), ensure_ascii=False, indent=1)[:3000])
