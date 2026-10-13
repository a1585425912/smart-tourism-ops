"""用 OpenAI 兼容接口批量生成合成游客评价, 作为演示网评语料。

生成内容是虚构的合成文本, 不代表真实游客意见; 入库后标记为 DEMO。
不包含任何个人信息(手机号/证件号等由播种程序另行构造用于测试脱敏)。

用法:
  set LLM_BASE_URL=http://localhost:3000/v1
  set LLM_API_KEY=sk-...
  set LLM_MODEL=deepseek-v4.1-flash
  python -I scripts/data/gen_reviews.py data/reviews/llm_reviews.csv
  (可选) set ONLY_SITES=BXDSJ,BXDSJ,TYD  只为指定景区再生成, 结果追加到已有文件
"""
import csv
import json
import os
import random
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("LLM_BASE_URL", "http://localhost:3000/v1").rstrip("/")
KEY = os.environ["LLM_API_KEY"]
MODEL = os.environ.get("LLM_MODEL", "deepseek-v4.1-flash")

SITES = {
    "BXDSJ": "哈尔滨冰雪大世界(冬季大型冰雪主题乐园: 冰雕冰建、超长冰滑梯、雪花摩天轮、夜间灯光、冰雪演艺、暖房餐饮; 门票较贵、旺季排队长、天气极冷)",
    "TYD": "太阳岛风景区(松花江北岸生态景区, 冬季雪博会大型雪雕、太阳湖、花卉园、游船码头; 白天游览为主、园区大需要步行)",
    "ZYDJ": "中央大街步行街(百年历史欧式建筑街区、面包石路、马迭尔冰棍、红肠、俄式西餐、文创店、江畔夜景; 开放式免费街区, 节假日人流密集)",
}

# 每批的侧重, 保证主题与情绪分布多样
FOCUS = [
    ("排队等候与人流拥挤", "以负面为主, 夹杂少量中性"),
    ("票价、套票与性价比", "正负面混合"),
    ("卫生间、垃圾与环境卫生", "正负面混合"),
    ("工作人员与志愿者服务态度", "以正面为主"),
    ("交通、打车与停车", "以负面为主"),
    ("餐饮价格与口味、热饮", "正负面混合"),
    ("游乐设施、暖房休息区、寄存与充电", "正负面混合"),
    ("安全与秩序、防滑、走失儿童", "混合, 包含少量表扬"),
    ("景观、冰雕雪雕、灯光与拍照体验", "以正面为主"),
    ("严寒天气、保暖与手机掉电", "正负面混合, 带实用建议"),
    ("带老人孩子的家庭游感受", "混合"),
    ("跟旅行团或导游讲解的体验", "混合"),
]

PROMPT = """你在为一个文旅管理系统生成演示用的合成游客点评语料。
景区: {site}
本批侧重: {focus}; 情绪分布: {mix}

请生成 {n} 条风格各异的中文游客点评, 要求:
- 像真实的 OTA/社交平台短评, 长度 15-90 字, 口语化, 有具体细节(时间段、项目、温度、价格等), 不要重复句式;
- 不要出现真实人名、手机号、证件号、网址;
- 每条给出 1-5 的评分(与情绪一致)和情绪 POSITIVE/NEUTRAL/NEGATIVE/MIXED。

只输出 JSON: {{"reviews": [{{"content": "...", "rating": 4, "sentiment": "POSITIVE"}}]}}"""


def call(site_code: str, focus: str, mix: str, n: int, seed: int) -> list[dict]:
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": PROMPT.format(site=SITES[site_code], focus=focus, mix=mix, n=n)}],
        "response_format": {"type": "json_object"},
        "temperature": 1.1,
        "max_tokens": 4000,
    }
    req = urllib.request.Request(BASE + "/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                data = json.loads(resp.read())
            text = data["choices"][0]["message"]["content"]
            items = json.loads(text)["reviews"]
            out = []
            for it in items:
                c = str(it.get("content", "")).strip().replace("\n", " ")
                r = int(it.get("rating", 3))
                s = str(it.get("sentiment", "NEUTRAL")).upper()
                if 8 <= len(c) <= 200 and 1 <= r <= 5 and s in {"POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED"}:
                    out.append({"site_code": site_code, "content": c, "rating": r, "sentiment": s, "focus": focus})
            return out
        except Exception as exc:  # 网关偶发错误时重试
            print(f"retry {site_code}/{focus}: {exc}", file=sys.stderr)
    return []


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "data/reviews/llm_reviews.csv"
    per_batch = int(os.environ.get("PER_BATCH", "20"))
    # ONLY_SITES=BXDSJ,BXDSJ,TYD 可重复指定以多生成几轮; 已有文件时追加并去重
    only = [x for x in os.environ.get("ONLY_SITES", ",".join(SITES)).split(",") if x in SITES]
    jobs = [(s, f, m, per_batch, i) for i, (s, (f, m)) in enumerate((s, fm) for s in only for fm in FOCUS)]
    rows = []
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as f:
            rows.extend(csv.DictReader(f))
    with ThreadPoolExecutor(max_workers=6) as pool:
        for batch in pool.map(lambda j: call(*j), jobs):
            rows.extend(batch)
    seen = set()
    unique = [r for r in rows if not (r["content"] in seen or seen.add(r["content"]))]
    random.Random(len(unique)).shuffle(unique)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["site_code", "content", "rating", "sentiment", "focus"])
        w.writeheader()
        w.writerows(unique)
    print(f"generated {len(unique)} reviews -> {out_path}")


if __name__ == "__main__":
    main()
