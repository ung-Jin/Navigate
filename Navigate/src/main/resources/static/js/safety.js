// 안전진단: 해양사고 위험 예보 지도 + 숫자 요약 — 볼 시각(when.js)이 든 3시간 예보
// 자료: /api/safety/risk?date=&time= (서버가 MTIS #261 을 그 시각만 받아 하루 동안 저장해 둠)
// map.js·when.js·traffic.js 다음에 불러와야 함 (map, currentWhen, trafficOn, currentTrafficGrids, trafficCellAt 등 사용)
//
// - 위험 등급 '낮음'(대부분)은 그리지 않고 보통·주의·경계 칸만 (위험한 곳만 남게)
// - 색은 상태색(노랑·주황·빨강) — 교통현황(보라)과 겹치지 않음
// - 칸 안을 색으로 채움. 교통현황 칸별 보기(줌 10)와 같이 켜면 더 옅게 채우고 테두리를 둘러 교통 칸이 보이게 함
// - 칸 안 어디에 마우스를 올려도 위험 정보 + (교통현황이 켜져 있으면) 마우스 아래 교통 칸 정보
//
// 교통 × 위험 결합 — 교통현황과 같은 시각끼리만 (볼 시각이 하나라 항상 같음)
//   지금: 위험 칸 안 선박 수 (실시간 #244 칸을 그 가운데가 들어 있는 위험 칸(4km)에 더함)
//   미래: 혼잡한 위험 칸 (혼잡 예보 #259 에서 보통 이상인 칸이 들어 있는 위험 칸)
//   가장 신경 쓸 곳: 등급 가중치(보통 1·주의 2·경계 3, 임시값) × 선박 수(지금) 또는 혼잡 지수 합(미래)

const RISK_COLORS = { '보통': '#eab308', '주의': '#f97316', '경계': '#dc2626' };
const RISK_WEIGHT = { '보통': 1, '주의': 2, '경계': 3 };
const RISK_HALF = [0.0187, 0.0225];  // 예보 칸 반지름 (위도, 경도) — 약 4.2 × 4.1km

// 교통 칸(400) 위, 교통 이름표(450) 아래. 칸이 칠해진 곳만 마우스를 받음 (빈 곳은 아래 교통 칸으로)
map.createPane('safetyRisk').style.zIndex = 420;
map.getPane('safetyRisk').style.pointerEvents = 'none';
const safetyRenderer = L.svg({ pane: 'safetyRisk' });
const safetyLayer = L.layerGroup().addTo(map);
let riskData = null;
let exposure = null;  // 교통 × 위험 결합 결과

const safetyOn = () => document.getElementById('sf-on').checked;
const fmtN = n => (n ?? 0).toLocaleString('ko-KR');

// ---------- 교통 × 위험 결합 ----------

function computeExposure() {
  const grids = currentTrafficGrids();
  if (!riskData || !riskData.grids || !grids) return null;
  const now = currentWhen().now;
  const amount = new Map();                     // 위험 칸 id → 선박 수(지금) / 혼잡 지수 합(미래)
  const byLevel = { '보통': 0, '주의': 0, '경계': 0 };  // 선박 수(지금) / 혼잡한 위험 칸 수(미래)
  grids.forEach(c => {
    if (!now && !isBusy(c)) return;
    const g = riskData.grids.find(r => Math.abs(c.lat - r.lat) <= RISK_HALF[0] && Math.abs(c.lng - r.lng) <= RISK_HALF[1]);
    if (!g || !(g.level in byLevel)) return;
    const first = !amount.has(g.id);
    amount.set(g.id, (amount.get(g.id) || 0) + (now ? c.vmtc : c.index || 0));
    if (now) byLevel[g.level] += c.vmtc;
    else if (first) byLevel[g.level] += 1;
  });
  let best = null;
  riskData.grids.forEach(g => {
    const score = (RISK_WEIGHT[g.level] || 0) * (amount.get(g.id) || 0);
    if (score > 0 && (!best || score > best.score)) best = { g, amount: amount.get(g.id), score };
  });
  return { now, amount, byLevel, best };
}

// ---------- 지도 ----------

function drawRisk() {
  safetyLayer.clearLayers();
  if (!safetyOn() || !riskData || !riskData.grids) return;

  const overTraffic = trafficOn() && Math.round(map.getZoom()) >= DETAIL_ZOOM;  // 교통 칸별 보기와 겹칠 때
  riskData.grids.forEach(g => {
    const color = RISK_COLORS[g.level];
    if (!color) return;
    const style = overTraffic
      ? { renderer: safetyRenderer, color, weight: 2, fillColor: color, fillOpacity: 0.35 }
      : { renderer: safetyRenderer, stroke: false, fillColor: color, fillOpacity: 0.55 };
    // 정보 창은 마우스를 따라다니며 마우스 아래 교통 칸이 바뀌면 같이 바뀜 (hoverTip: traffic.js)
    hoverTip(L.rectangle([[g.lat - RISK_HALF[0], g.lng - RISK_HALF[1]], [g.lat + RISK_HALF[0], g.lng + RISK_HALF[1]]], style),
      at => riskTip(g, at))
      .addTo(safetyLayer);
  });
}

// 풍향 → 화살표 + 8방위 이름 (예: ↓ 북풍)
// 풍향 값은 바람이 "불어오는" 방향(기상 관례, 0° = 북쪽에서 부는 바람)으로 봄
// 화살표는 바람이 "불어 가는" 쪽을 가리킴 → 북풍이면 아래(남쪽)
const WIND_NAMES = ['북풍', '북동풍', '동풍', '남동풍', '남풍', '남서풍', '서풍', '북서풍'];
function windText(deg) {
  if (deg == null) return '바람 -';
  const name = WIND_NAMES[Math.round(deg / 45) % 8];
  return `바람 <span class="wind-arrow" style="transform:rotate(${(deg + 180) % 360}deg)">↑</span> ${name}`;
}

// 위험 정보 + 이 위험 칸 안 교통(결합) + (교통현황이 켜져 있으면) 마우스 아래 교통 칸
function riskTip(g, at) {
  let html = `<b>${g.near ?? ''}</b><br>사고위험 <b style="color:${RISK_COLORS[g.level] ?? '#374151'}">${g.level}</b>`
    + `<br>파고 ${g.wave ?? '-'}m · 풍속 ${g.wind ?? '-'}m/s<br>${windText(g.windDir)}`;
  if (exposure && exposure.now) {
    html += `<br>이 위험 칸(4km) 안 선박 <b>${fmtN(exposure.amount.get(g.id))}척</b>`;
  } else if (exposure) {
    html += `<br>이 위험 칸(4km) 안 혼잡 지수 합 <b>${(exposure.amount.get(g.id) || 0).toFixed(1)}%</b>`;
  }
  const cell = at && trafficOn() ? trafficCellAt(at.lat, at.lng) : null;
  if (cell) {
    html += `<hr class="tip-hr">교통 칸(2.8km): ${trafficCellText(cell)}`;
  }
  return html;
}

// ---------- 패널 ----------

function drawRiskLegend() {
  document.getElementById('sf-legend').innerHTML = Object.entries(RISK_COLORS)
    .map(([lv, c]) => `<span class="chip"><i style="background:${c}"></i>${lv}</span>`).join('')
    + '<span class="unit">· 낮음은 표시 안 함</span>';
}

function renderSafetyPanel() {
  const d = riskData || {};
  const c = d.counts || {};
  const set = (id, text) => { document.getElementById(id).textContent = text; };
  exposure = computeExposure();

  set('sf-time', d.fcstDate ? `${d.fcstDate.slice(4, 6)}-${d.fcstDate.slice(6)} ${d.fcstTime.slice(0, 2)}시 예보 (3시간 단위)` : '사고위험 예보');
  set('sf-n-high', c['경계'] != null ? fmtN(c['경계']) + '칸' : '-');
  set('sf-n-mid', c['주의'] != null ? fmtN(c['주의']) + '칸' : '-');

  // 결합 숫자
  const now = currentWhen().now;
  set('sf-expo-k', now ? '위험 칸(경계·주의) 안 선박' : '혼잡한 위험 칸 (경계·주의)');
  if (exposure) {
    const b = exposure.byLevel;
    const unit = now ? '척' : '칸';
    set('sf-expo-n', fmtN(b['경계'] + b['주의']) + unit);
    set('sf-expo-s', `경계 ${fmtN(b['경계'])} · 주의 ${fmtN(b['주의'])} · 보통 ${fmtN(b['보통'])}${unit}`
      + (now ? '' : ' (혼잡 예보 보통 이상인 칸이 든 곳)'));
  } else {
    set('sf-expo-n', '-');
    set('sf-expo-s', d.fcstDate ? (now ? '실시간 교통 자료를 기다리는 중' : '혼잡 예보를 기다리는 중') : '');
  }

  // 가장 신경 쓸 곳 (결합) / 결합 못 하면 예보 지수 기준
  const best = exposure && exposure.best;
  const spot = best ? best.g : d.top;
  set('sf-top-k', best ? (now ? '배가 많은 위험한 곳' : '혼잡한 위험한 곳') : '가장 위험한 곳 (예보 지수)');
  set('sf-top-n', spot ? `${spot.near ?? ''} · ${spot.level}` : '-');
  const extra = best ? (now ? `선박 ${fmtN(best.amount)}척 · ` : `혼잡 지수 합 ${best.amount.toFixed(1)}% · `) : '';
  document.getElementById('sf-top-s').innerHTML = spot
    ? extra + `파고 ${spot.wave ?? '-'}m · 풍속 ${spot.wind ?? '-'}m/s · ${windText(spot.windDir)}`
    : '';
  set('sf-msg', d.error ?? '');
}

// 볼 시각이 든 3시간 위험 예보 받기 (같은 3시간 칸이면 다시 받지 않음)
function loadRisk() {
  if (!safetyOn()) return;
  const w = currentWhen();
  const slot = String(Math.floor(Number(w.hour) / 3) * 3).padStart(2, '0') + '00';
  if (riskData && riskData.fcstDate === w.date && riskData.fcstTime === slot && !riskData.error) {
    renderSafetyPanel();
    drawRisk();
    return;
  }
  document.getElementById('sf-msg').textContent = '';
  axios.get('/api/safety/risk', { params: { date: w.date, time: w.hour } })
    .then(response => {
      const w2 = currentWhen();
      if (w2.date !== w.date || w2.hour !== w.hour) return;  // 그사이 시각을 바꿨으면 버림
      riskData = response.data;
      renderSafetyPanel();
      drawRisk();
    })
    .catch(err => {
      document.getElementById('sf-msg').textContent = '사고위험 예보를 불러오지 못했습니다';
      console.log('사고위험 예보를 불러오지 못했습니다.', err);
    });
}

// 켜기 / 끄기 — 끄면 패널이 한 줄로 접히고 지도에서 지움
document.getElementById('sf-on').addEventListener('change', () => {
  document.getElementById('safety-panel').classList.toggle('collapsed', !safetyOn());
  if (safetyOn()) loadRisk(); else drawRisk();
});

// 볼 시각이 바뀌면 그 시각 예보
document.addEventListener('when-changed', loadRisk);

// 교통 칸별 보기와 겹치는지에 따라 채우기 ↔ 테두리가 바뀌므로 줌이 바뀌면 다시 그림
map.on('zoomend', drawRisk);
document.getElementById('tr-on').addEventListener('change', drawRisk);

// 교통 자료가 바뀌면(실시간 1분마다 확인, 혼잡 예보 도착) 결합 숫자 다시 계산
document.addEventListener('traffic-updated', () => {
  if (riskData) renderSafetyPanel();
});

// 카드 누르면 그 위험 칸으로 이동
document.getElementById('sf-top').addEventListener('click', () => {
  const spot = exposure && exposure.best ? exposure.best.g : riskData && riskData.top;
  if (spot) map.flyTo([spot.lat, spot.lng], 9);
});

drawRiskLegend();
document.getElementById('safety-panel').classList.toggle('collapsed', !safetyOn());
