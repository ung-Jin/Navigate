# -*- coding: utf-8 -*-
"""
make_sea_grid.py — 110m 해안선 격자를 MTIS 해양격자(0.025°) 크기로 줄인다.

    python tools/make_sea_grid.py

왜 필요한가
  경로를 찾으려면 "이 칸이 바다인가"를 알아야 한다.
  OpenStreetMap 해안선으로 만든 110m 격자(sea_fine.bin, 56MB)가 이미 있지만,
  56MB 를 깃에 올리기 부담스럽고 A* 가 탐색할 칸도 너무 많다.
  그래서 MTIS 해양격자와 같은 0.025° 칸으로 줄여서 쓴다 (88KB).

  MTIS 격자와 칸을 맞추는 이유: 교통량·혼잡 예보·사고위험 예보가 모두 그 격자로 오기 때문.
  칸 번호가 같으면 위험도를 그대로 가져다 쓸 수 있다 (변환 코드가 필요 없음).

입력  ../../3차프로젝트/data/raw/mtis/derived/sea_fine.bin   (7000 x 8000, 1바이트 = 1칸, 값 1이면 바다)
출력  src/main/resources/sea/sea_grid.bin                    (280 x 320, 1바이트 = 그 칸의 바다 비율 0~255)

바다 '비율'을 저장하는 이유
  2.5km 칸은 커서 "절반은 바다, 절반은 육지"인 칸이 많다 (특히 항구 근처).
  바다/육지 를 0·1 로만 저장하면 항구가 있는 칸이 육지로 분류되어 경로를 시작할 수 없다.
  비율로 저장해 두면 Java 쪽에서 기준값을 조절할 수 있고, A* 가 '육지가 많이 낀 칸'을
  더 비싸게 계산해서 대형선이 연안을 피하도록 만들 수도 있다.
"""
import os

import numpy as np

# ---------- 격자 설정 ----------
# 입력(110m 격자) — 3차프로젝트 dashboard/coast.py 와 같은 값이어야 한다
FINE = 0.001
FINE_ROWS = 7000          # (39.0 - 32.0) / 0.001
FINE_COLS = 8000          # (132.0 - 124.0) / 0.001

# 출력(MTIS 격자) — MtisGrid.java 의 한 칸 크기와 같다
CELL = 0.025
BLOCK = round(CELL / FINE)        # 25 — 110m 칸 25x25 가 2.5km 칸 하나
ROWS = FINE_ROWS // BLOCK         # 280
COLS = FINE_COLS // BLOCK         # 320

# 격자가 덮는 범위 (남쪽 위도, 서쪽 경도) — Java 에서도 똑같이 써야 한다
LAT0 = 32.0
LON0 = 124.0

HERE = os.path.dirname(os.path.abspath(__file__))
NAVIGATE = os.path.dirname(HERE)
TEAM_ROOT = os.path.dirname(os.path.dirname(NAVIGATE))

IN_PATH = os.path.join(TEAM_ROOT, "3차프로젝트", "data", "raw", "mtis", "derived", "sea_fine.bin")
OUT_DIR = os.path.join(NAVIGATE, "src", "main", "resources", "sea")
OUT_PATH = os.path.join(OUT_DIR, "sea_grid.bin")


def main():
    if not os.path.exists(IN_PATH):
        raise SystemExit("원본 격자를 찾을 수 없습니다: " + IN_PATH)

    # ---------- 1. 110m 격자 읽기 ----------
    # 파일 전체가 1바이트씩 이어 붙은 숫자들이다. 7000 x 8000 모양으로 바꿔서 읽는다.
    fine = np.fromfile(IN_PATH, dtype=np.uint8)
    if fine.size != FINE_ROWS * FINE_COLS:
        raise SystemExit(f"크기가 맞지 않습니다: {fine.size} (기대값 {FINE_ROWS * FINE_COLS})")
    fine = fine.reshape(FINE_ROWS, FINE_COLS)

    # 값이 1인 칸만 바다다. True/False 로 바꾼다.
    is_sea = (fine == 1)
    print(f"110m 격자: {FINE_ROWS} x {FINE_COLS} · 바다 {is_sea.sum():,}칸")

    # ---------- 2. 2.5km 칸마다 바다 비율 구하기 ----------
    # (280, 25, 320, 25) 모양으로 쪼갠 뒤, 25x25 묶음마다 평균을 낸다.
    # 평균 = 그 묶음에서 바다인 칸의 비율 (0.0 ~ 1.0)
    frac = is_sea.reshape(ROWS, BLOCK, COLS, BLOCK).mean(axis=(1, 3))

    # ---------- 3. 0~255 로 바꿔서 저장 ----------
    # 소수를 그대로 저장하면 칸당 8바이트(=700KB)지만, 0~255 정수로 바꾸면 1바이트(=88KB)로 줄어든다.
    # 비율을 255단계로 나눠 저장하는 것이라 정밀도 손해는 거의 없다.
    out = np.round(frac * 255).astype(np.uint8)

    os.makedirs(OUT_DIR, exist_ok=True)
    out.tofile(OUT_PATH)

    # ---------- 4. 결과 확인 ----------
    print(f"MTIS 격자: {ROWS} x {COLS} = {ROWS * COLS:,}칸")
    print(f"  바다가 조금이라도 있는 칸 (비율 >  0%): {(frac > 0.00).sum():,}")
    print(f"  통과 가능으로 볼 만한 칸   (비율 > 20%): {(frac > 0.20).sum():,}")
    print(f"  거의 바다인 칸             (비율 > 80%): {(frac > 0.80).sum():,}")
    print(f"저장: {OUT_PATH}  ({os.path.getsize(OUT_PATH):,} 바이트)")

    # 눈으로 확인 — 아는 지점 몇 곳이 바다/육지로 제대로 나오는지
    print()
    print("확인 (바다 비율):")
    for name, lat, lon in [
        ("부산항",   35.100, 129.033),
        ("목포항",   34.767, 126.358),
        ("울산항",   35.502, 129.387),
        ("제주 남쪽 먼바다", 33.000, 126.500),
        ("서울 (육지)",     37.566, 126.978),
        ("대구 (육지)",     35.871, 128.601),
    ]:
        r = int((lat - LAT0) / CELL)
        c = int((lon - LON0) / CELL)
        print(f"  {name:<18} {frac[r, c] * 100:5.1f}%")


if __name__ == "__main__":
    main()
