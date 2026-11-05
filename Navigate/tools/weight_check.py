# -*- coding: utf-8 -*-
"""
weight_check.py — 위험도 가중치가 실제 사고다발 지점을 얼마나 잘 맞추는지 잰다.

    python tools/weight_check.py

왜 필요한가
  RiskMap 의 가중치(교통·사고위험 예보·기상)는 처음에 상식으로 정한 값이었다.
  "왜 그 숫자냐" 에 답하려면 근거가 있어야 해서, 실제 사고 자료로 채점한다.

어떻게 채점하나
  MTIS 사고다발 격자(#243)를 정답지로 쓴다. MTIS 가 "여기는 실제로 사고가 많이 난 곳" 이라고
  지정한 칸이므로, 우리 위험도가 제대로 매겨졌다면 그 칸들이 높은 점수를 받아야 한다.

  1. 칸마다 교통·예보·기상 점수(0~1)를 구한다       (RiskMap 과 같은 방식)
  2. 가중치를 곱해 더해 그 칸의 위험도를 만든다
  3. 모든 칸을 위험도 순으로 줄 세운다
  4. 사고다발 칸이 몇 등인지 본다 — 앞쪽일수록 좋은 가중치

  ** 사고다발은 위험도 계산에 넣지 않는다 **
  넣으면서 동시에 채점하면 시험 문제를 알려주고 시험 보는 꼴이라 당연히 만점이 나온다.

읽는 법
  AUC  사고다발 칸 하나와 아무 칸 하나를 뽑았을 때 사고다발 쪽 점수가 더 높을 확률
       0.5 = 동전 던지기(의미 없음) · 1.0 = 사고다발 칸이 전부 최상위

알아 둘 것
  cache/ 에 저장된 예보를 읽으므로, 앱을 한 번 띄워 그 시각 예보를 받아 둔 뒤에 돌려야 한다.
  측정한 날 날씨가 잔잔하면 기상 항목이 거의 0 이 되어 기상 비중은 평가할 수 없다.
  그런 날은 "교통 : 예보" 비율만 비교하는 셈이므로, 날씨가 나쁜 날 다시 돌려야 한다.
"""
import glob
import json
import math
import os
import sys

# ---------- RiskMap.java 와 같은 값이어야 한다 ----------
MTIS_CELL = 0.025
LAT0, LON0 = 32.0, 124.0
MTIS_COLS = 320
RISK_HALF_LAT, RISK_HALF_LNG = 0.0187, 0.0225       # 사고위험 예보 칸 반폭 (약 4.2 x 4.1km)
WAVE_LIMIT, WIND_LIMIT = 4.0, 20.0                  # 화물선·유조선 기상 한계
LEVEL = {"낮음": 0.0, "보통": 0.33, "높음": 0.66, "매우높음": 1.0,
         "주의": 0.66, "경계": 1.0}

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(os.path.dirname(HERE), "cache")

# 시험해 볼 조합 (이름, 교통, 예보, 기상)
COMBOS = [
    ("35/35/30 (처음)", 0.35, 0.35, 0.30),
    ("50/30/20 (지금)", 0.50, 0.30, 0.20),
    ("55/25/20", 0.55, 0.25, 0.20),
    ("60/20/20", 0.60, 0.20, 0.20),
    ("30/50/20", 0.30, 0.50, 0.20),
    ("20/60/20", 0.20, 0.60, 0.20),
    ("교통만 100", 1.0, 0.0, 0.0),
    ("예보만 100", 0.0, 1.0, 0.0),
    ("기상만 100", 0.0, 0.0, 1.0),
]


def cell_key(lat, lng):
    """위경도 -> MTIS 격자 칸 번호 (RiskMap.mtisKey 와 같다)"""
    r = int(math.floor((lat - LAT0) / MTIS_CELL))
    c = int(math.floor((lng - LON0) / MTIS_CELL))
    return r * MTIS_COLS + c


def over_limit(value, limit):
    """한계의 절반까지는 0, 한계에서 1 (RiskMap.ratio 와 같다)"""
    if value is None or value <= limit / 2:
        return 0.0
    return min(1.0, (value - limit / 2) / (limit / 2))


def weather_score(wave, wind):
    return max(over_limit(wave, WAVE_LIMIT), over_limit(wind, WIND_LIMIT))


def newest(sub):
    files = sorted(glob.glob(os.path.join(CACHE, sub, "*.json")))
    return files[-1] if files else None


def load_scores():
    """cache/ 에서 예보를 읽어 칸마다 교통·예보·기상 점수를 만든다."""
    cg_path, rk_path, hs_path = newest("congestion"), newest("risk"), newest("hotspot")
    missing = [n for n, p in (("congestion", cg_path), ("risk", rk_path), ("hotspot", hs_path)) if not p]
    if missing:
        raise SystemExit(f"cache/{'·'.join(missing)} 에 자료가 없습니다. 앱을 띄워 자료를 받아 두세요.")

    cg = json.load(open(cg_path, encoding="utf-8"))
    rk = json.load(open(rk_path, encoding="utf-8"))
    hs = json.load(open(hs_path, encoding="utf-8"))

    traffic, forecast, weather = {}, {}, {}

    # 혼잡 예보 — 교통 + 기상
    for g in (cg.get("grids") or []):
        if g.get("lat") is None:
            continue
        k = cell_key(g["lat"], g["lng"])
        traffic[k] = max(traffic.get(k, 0.0), LEVEL.get(g.get("level"), 0.0))
        weather[k] = max(weather.get(k, 0.0), weather_score(g.get("wave"), g.get("wind")))

    # 사고위험 예보 — 칸이 4.2km 라 2.5km 칸 여러 개를 덮는다 (RiskMap.paint 와 같다)
    for g in (rk.get("grids") or []):
        if g.get("lat") is None:
            continue
        score = LEVEL.get(g.get("level"), 0.0)
        wx = weather_score(g.get("wave"), g.get("wind"))
        lat = g["lat"] - RISK_HALF_LAT
        while lat <= g["lat"] + RISK_HALF_LAT:
            lng = g["lng"] - RISK_HALF_LNG
            while lng <= g["lng"] + RISK_HALF_LNG:
                k = cell_key(lat, lng)
                forecast[k] = max(forecast.get(k, 0.0), score)
                weather[k] = max(weather.get(k, 0.0), wx)
                lng += MTIS_CELL / 2
            lat += MTIS_CELL / 2

    hotspots = {cell_key(g["lat"], g["lng"]) for g in (hs.get("grids") or []) if g.get("lat") is not None}

    info = (f"혼잡 예보 {cg.get('fcstDate')} {cg.get('fcstTime')} · "
            f"사고위험 {rk.get('fcstDate')} {rk.get('fcstTime')} · "
            f"사고다발 {hs.get('year')}년")
    return traffic, forecast, weather, hotspots, info


def evaluate(traffic, forecast, weather, hotspots, wt, wf, ww):
    """한 조합으로 줄을 세우고, 사고다발 칸이 몇 등인지 본다."""
    score = {}
    for k in set(traffic) | set(forecast) | set(weather):
        v = wt * traffic.get(k, 0.0) + wf * forecast.get(k, 0.0) + ww * weather.get(k, 0.0)
        if v > 0:
            score[k] = min(1.0, v)
    if not score:
        return None

    ordered = sorted(score.items(), key=lambda x: -x[1])
    total = len(ordered)
    rank = {k: i for i, (k, _) in enumerate(ordered)}

    hits = [k for k in hotspots if k in rank]
    if not hits:
        return None

    ranks = sorted(rank[k] for k in hits)
    return {
        "hotAvg": sum(score[k] for k in hits) / len(hits),
        "allAvg": sum(score.values()) / total,
        "top10": sum(1 for r in ranks if r < total * 0.10) / len(ranks),
        "top25": sum(1 for r in ranks if r < total * 0.25) / len(ranks),
        "median": ranks[len(ranks) // 2] + 1,
        "auc": sum(1 - r / total for r in ranks) / len(ranks),
        "total": total,
    }


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    traffic, forecast, weather, hotspots, info = load_scores()

    cells = set(traffic) | set(forecast) | set(weather)
    wx_cells = sum(1 for v in weather.values() if v > 0)

    print(info)
    print(f"위험 자료가 있는 칸 {len(cells):,} · 정답지(사고다발) {len(hotspots)}칸")
    print(f"기상 점수가 0 이 아닌 칸 {wx_cells} / {len(cells):,}")
    if wx_cells < len(cells) * 0.01:
        print("  ** 날씨가 잔잔해 기상 항목이 거의 0 입니다. 기상 비중은 이 결과로 판단할 수 없습니다 **")
    print()

    print(f'{"조합":<18}{"사고다발평균":>13}{"전체평균":>10}{"상위10%":>9}{"상위25%":>9}{"중앙등수":>10}{"AUC":>8}')
    print("-" * 80)
    rows = []
    for name, wt, wf, ww in COMBOS:
        r = evaluate(traffic, forecast, weather, hotspots, wt, wf, ww)
        if r is None:
            print(f"{name:<18}  (계산 불가 — 그 요소가 전부 0)")
            continue
        rows.append((r["auc"], name))
        print(f'{name:<18}{r["hotAvg"]:>13.3f}{r["allAvg"]:>10.3f}'
              f'{r["top10"] * 100:>8.0f}%{r["top25"] * 100:>8.0f}%'
              f'{r["median"]:>7}/{r["total"]}{r["auc"]:>8.3f}')

    print()
    rows.sort(reverse=True)
    print("AUC 높은 순: " + " > ".join(n for _, n in rows[:4]))
    print()
    print("AUC 0.5 = 무작위와 같음 · 1.0 = 사고다발 칸이 전부 최상위")


if __name__ == "__main__":
    main()
