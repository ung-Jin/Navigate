// 교통현황: 선박 지도 + 숫자 요약 — 볼 시각(when.js)에 따라
//   지금 : /api/traffic/realtime  실시간 선박 수·밀집도 (서버가 10분마다 MTIS #244 에서 받아 둠)
//   미래 : /api/traffic/forecast  혼잡 예보 (MTIS #259, 1시간 단위) — 선박 수는 없고 혼잡 지수(%)·단계만
// map.js·when.js 다음에 불러와야 함
//
// 지도
//   - 줌 8~9: 해역 14곳 이름표 (지금 = 선박 수, 미래 = 혼잡 칸 수). 마우스 올리면 해역 범위, 누르면 칸별 보기로
//   - 줌 10 : 칸(2.8km)마다 흰 경계선 + 칸 색 = 밀집도(지금)·혼잡 지수(미래), 숫자 = 선박 수(지금만, 1척은 점)

const CELL_HALF = 0.0125;  // 칸 반지름 (도) — 한 칸 0.025°
const SHIP_COLOR = '#5b21b6';  // 보라 — 항구(검정 ⚓·흰 이름표), 바다(파랑)와 구분. 주황·빨강은 안전진단(위험 단계)용으로 남겨 둠

// 밀집도·혼잡 지수 색 (보라 한 가지 색의 진하기, 큰 기준부터 확인) — KOMSA 혼잡 예보 단계 기준 1·5·10·20%
// dark = 진한 칸이라 숫자를 흰 글자로
const DENSITY_STEPS = [
  { min: 20, color: '#4c1d95', dark: true },
  { min: 10, color: '#7c3aed', dark: true },
  { min: 5, color: '#a78bfa', dark: false },
  { min: 1, color: '#c4b5fd', dark: false },
  { min: 0, color: '#ede9fe', dark: false },
];
const densityStep = v => DENSITY_STEPS.find(s => (v ?? 0) >= s.min) || DENSITY_STEPS[DENSITY_STEPS.length - 1];

// 해역: 칸은 가장 가까운 거점(hub)의 해역에 들어감, 이름표는 at 위치(바다 쪽)에 표시
// at 은 항구 이름표(⚓ 오른쪽으로 뻗음)와 겹치지 않게 잡음 — 줌 8·9에서 겹침 점검함
const ZONES = [
  { name: '인천·경기만', hub: [37.45, 126.55], at: [37.30, 125.95] },
  { name: '평택·대산·태안', hub: [36.90, 126.30], at: [36.75, 125.75] },
  { name: '보령·군산', hub: [36.10, 126.45], at: [35.95, 125.95] },
  { name: '목포·신안', hub: [34.85, 126.20], at: [35.05, 125.55] },
  { name: '진도·완도', hub: [34.30, 126.60], at: [33.95, 126.05] },
  { name: '여수·광양', hub: [34.75, 127.75], at: [34.25, 127.45] },
  { name: '통영·거제', hub: [34.85, 128.50], at: [34.45, 128.35] },
  { name: '부산·진해', hub: [35.05, 128.95], at: [34.85, 129.35] },
  { name: '울산', hub: [35.45, 129.40], at: [35.45, 130.10] },
  { name: '포항·경주', hub: [36.05, 129.45], at: [36.10, 130.10] },
  { name: '동해·삼척', hub: [37.45, 129.15], at: [37.30, 130.00] },
  { name: '강릉·속초', hub: [38.10, 128.65], at: [38.10, 129.20] },
  { name: '울릉·독도', hub: [37.45, 130.90], at: [37.15, 130.90] },
  { name: '제주', hub: [33.40, 126.55], at: [33.30, 125.90] },
];
const DETAIL_ZOOM = 10;   // 이 줌부터는 해역 이름표 대신 칸별 보기
const CELL_LABEL = 2;     // 칸별 보기에서 숫자를 쓰는 최소 척수 (1척은 점)
const BUSY_LEVELS = ['보통', '높음', '매우높음'];  // 혼잡 예보에서 "혼잡 칸"으로 세는 단계

map.createPane('trafficLabel').style.zIndex = 450;  // 해역 이름표: 칸(400) 위, 항구 마커(600) 아래 — 항구 클릭이 가려지지 않게
map.createPane('trafficNum').style.zIndex = 660;    // 칸 숫자: 항구 이름표(650) 위에 보이게 (클릭 안 받음 → 항구 클릭은 그대로)
map.createPane('infoTip').style.zIndex = 700;       // 마우스 올리면 뜨는 정보 창: 칸 숫자(660)보다 위 — 글자가 가려지지 않게 (교통·안전진단 공통)

// 마우스 올리면 뜨는 정보 창 — 화면에 항상 하나만, 직접 열고 닫음 (교통·안전진단 공통)
// Leaflet bindTooltip 은 지도를 끄는 중에 칸에 마우스가 닿으면 끌기가 끝난 뒤에 열어서,
// 그사이 마우스가 떠났으면 닫히지 않고 정보 창이 쌓임 → 끄는 중엔 열지 않고, 새로 열 땐 앞의 창을 닫음
let openInfoTip = null;
function closeInfoTip() {
  if (openInfoTip) map.closeTooltip(openInfoTip);
  openInfoTip = null;
}
// content: 문자열 또는 (마우스 위치) => 문자열. sticky 면 마우스를 따라다니고, 아니면 at 위치에 고정
function hoverTip(layer, content, { sticky = true, at = null, ...opts } = {}) {
  const tip = L.tooltip({ pane: 'infoTip', ...opts });
  const show = e => {
    if (map.dragging.moving()) return;
    tip.setContent(typeof content === 'function' ? content(e.latlng) : content);
    if (openInfoTip !== tip) {
      closeInfoTip();
      map.openTooltip(tip, sticky ? e.latlng : at);
      openInfoTip = tip;
    } else if (sticky) {
      tip.setLatLng(e.latlng);
    }
  };
  layer.on('mouseover', show);
  if (sticky) layer.on('mousemove', show);  // 끌기가 끝난 뒤 움직여도 다시 뜸
  layer.on('mouseout remove', () => { if (openInfoTip === tip) closeInfoTip(); });
  return layer;
}
map.on('dragstart zoomstart', closeInfoTip);

const trafficRenderer = L.canvas({ padding: 0.5 });  // 칸이 많아 canvas 로 그림
const trafficLayer = L.layerGroup().addTo(map);
let trafficData = null;      // 실시간 (지금)
let trafficForecast = null;  // 혼잡 예보 (미래, 고른 시각)
let trafficZones = [];
let trafficIndex = new Map();  // 칸 위치 → 칸 (안전진단에서 위치로 교통 칸 찾기용)

const trFmt = n => (n ?? 0).toLocaleString('ko-KR');
const trafficOn = () => document.getElementById('tr-on').checked;  // 패널 제목의 체크박스
const cellBox = g => [[g.lat - CELL_HALF, g.lng - CELL_HALF], [g.lat + CELL_HALF, g.lng + CELL_HALF]];
const cellKey = (lat, lng) => Math.floor(lat / (CELL_HALF * 2)) + '_' + Math.floor(lng / (CELL_HALF * 2));
const isBusy = g => BUSY_LEVELS.includes(g.level);

// 지금 보고 있는 시각의 교통 칸 목록 (지금 = 실시간, 미래 = 혼잡 예보)
function currentTrafficGrids() {
  const d = currentWhen().now ? trafficData : trafficForecast;
  return d && d.grids ? d.grids : null;
}

// 좌표가 들어 있는 교통 칸 (없으면 null) — 지금 보는 시각 기준
function trafficCellAt(lat, lng) {
  return trafficIndex.get(cellKey(lat, lng)) || null;
}

// 교통 칸 설명 (지도·안전진단 툴팁 공통)
function trafficCellText(g) {
  return g.vmtc != null
    ? `선박 <b>${trFmt(g.vmtc)}척</b> · 밀집도 <b>${g.dnsty ?? '-'}%</b>`
    : `혼잡 예보 <b>${g.level ?? '-'}</b> · 지수 <b>${g.index ?? '-'}%</b>`;
}
const gridTip = g => `<b>${g.near ?? ''}</b><br>${trafficCellText(g)}`;

// 지금 보는 시각 자료로 해역·칸 찾기표 다시 만들기
function rebuildTraffic() {
  const grids = currentTrafficGrids() || [];
  trafficIndex = new Map(grids.map(g => [cellKey(g.lat, g.lng), g]));
  trafficZones = grids.length ? buildZones(grids) : [];
}

// 지도에 그리기
function drawTraffic() {
  trafficLayer.clearLayers();
  if (!trafficOn() || !currentTrafficGrids()) return;
  if (Math.round(map.getZoom()) >= DETAIL_ZOOM) return drawCells();
  drawZones();
}

// ---------- 해역 ----------

// 칸마다 가장 가까운 거점의 해역으로 묶음. 값 = 선박 수 합(지금) / 혼잡 칸 수(미래). 큰 해역부터
function buildZones(grids) {
  const now = currentWhen().now;
  const value = g => now ? g.vmtc : g.index;  // 해역에서 가장 붐비는 칸 고르는 기준
  const zones = ZONES.map(z => ({ ...z, n: 0, cells: [], top: null }));
  grids.forEach(g => {
    let best = null, bestKm = Infinity;
    zones.forEach(z => {
      const km = Math.hypot((g.lat - z.hub[0]) * 111, (g.lng - z.hub[1]) * 111 * Math.cos(g.lat * Math.PI / 180));
      if (km < bestKm) { bestKm = km; best = z; }
    });
    best.n += now ? g.vmtc : (isBusy(g) ? 1 : 0);
    best.cells.push(g);
    if (!best.top || value(g) > value(best.top)) best.top = g;
  });
  zones.forEach(z => { z.hull = z.cells.length ? convexHull(z.cells) : []; });
  return zones.sort((a, b) => b.n - a.n);
}

// 칸 가운데 점들의 볼록 껍질 → 해역 범위 표시용 [[lat, lng], ...] (칸 반지름만큼 넓힘)
function convexHull(cells) {
  const pts = [];
  cells.forEach(g => [[-1, -1], [-1, 1], [1, -1], [1, 1]].forEach(([a, b]) =>
    pts.push([g.lng + a * CELL_HALF, g.lat + b * CELL_HALF])));
  pts.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const half = list => {
    const h = [];
    list.forEach(p => {
      while (h.length >= 2 && cross(h[h.length - 2], h[h.length - 1], p) <= 0) h.pop();
      h.push(p);
    });
    h.pop();
    return h;
  };
  return [...half(pts), ...half([...pts].reverse())].map(([lng, lat]) => [lat, lng]);
}

// 해역 이름표 (상위 3곳은 진하게)
function drawZones() {
  const now = currentWhen().now;
  const hullLayer = L.layerGroup().addTo(trafficLayer);  // 마우스 올린 해역의 범위
  trafficZones.forEach((z, rank) => {
    if (!z.n) return;
    const value = now ? trFmt(z.n) : trFmt(z.n) + '칸';
    const tip = now
      ? `<b>${z.name}</b> · ${rank + 1}위<br>선박 <b>${trFmt(z.n)}척</b> · 칸 ${trFmt(z.cells.length)}개`
        + `<br>가장 붐비는 칸 ${trFmt(z.top.vmtc)}척 (${z.top.near ?? ''})`
      : `<b>${z.name}</b> · ${rank + 1}위 (${whenLabel()} 예보)<br>혼잡 칸(보통 이상) <b>${trFmt(z.n)}개</b> / 전체 ${trFmt(z.cells.length)}칸`
        + `<br>가장 혼잡한 칸 ${z.top.level} · 지수 ${z.top.index}% (${z.top.near ?? ''})`;
    const label = L.marker(z.at, {
      pane: 'trafficLabel', keyboard: false, zIndexOffset: z.n,
      icon: L.divIcon({
        className: 'tr-zone-wrap', iconSize: null,
        html: `<div class="tr-zone${rank < 3 ? ' top' : ''}"><b>${value}</b><span>${z.name}</span></div>`
      })
    });
    hoverTip(label, tip + '<br>누르면 칸별로 확대', { sticky: false, at: z.at, direction: 'top', offset: [0, -14] })
      .on('mouseover', () => {
        L.polygon(z.hull, { color: SHIP_COLOR, weight: 1.5, dashArray: '4 4', fillOpacity: 0.06, interactive: false })
          .addTo(hullLayer);
      })
      .on('mouseout', () => hullLayer.clearLayers())
      .on('click', () => map.setView([z.top.lat, z.top.lng], DETAIL_ZOOM))  // 해역의 가장 붐비는 칸을 가운데로
      .addTo(trafficLayer);
  });
}

// ---------- 칸별 (줌 10) ----------

// 화면 안 칸만. 칸 사이 흰 경계선, 칸 색 = 밀집도(지금)·혼잡 지수(미래), 숫자 = 선박 수(지금만, 1척은 점)
function drawCells() {
  const now = currentWhen().now;
  const bounds = map.getBounds().pad(0.1);
  currentTrafficGrids().forEach(g => {
    if (!bounds.contains([g.lat, g.lng])) return;
    if (now && !g.vmtc) return;
    const step = densityStep(now ? g.dnsty : g.index);
    hoverTip(L.rectangle(cellBox(g), {
      renderer: trafficRenderer, color: '#ffffff', weight: 1.5, opacity: 1,
      fillColor: step.color, fillOpacity: 0.85
    }), gridTip(g)).addTo(trafficLayer);
    if (!now) return;  // 예보에는 선박 수가 없음
    const html = g.vmtc >= CELL_LABEL ? `<span>${g.vmtc}</span>` : '<span class="dot"></span>';
    L.marker([g.lat, g.lng], {
      pane: 'trafficNum', keyboard: false, interactive: false,
      icon: L.divIcon({ className: 'tr-cell-num' + (step.dark ? ' on-dark' : ''), iconSize: null, html })
    }).addTo(trafficLayer);
  });
}

// ---------- 패널 ----------

function drawLegend() {
  const box = document.getElementById('tr-legend');
  const now = currentWhen().now;
  if (!trafficOn()) {
    box.innerHTML = '';
    return;
  }
  if (Math.round(map.getZoom()) >= DETAIL_ZOOM) {
    const steps = [...DENSITY_STEPS].reverse();  // 옅은 것부터
    box.innerHTML = (now ? '<span class="unit">숫자 = 선박 수 · 점(•) = 1척</span>' : '<span class="unit">예보에는 선박 수가 없음</span>')
      + `<span class="legend-row"><span class="unit">색 = ${now ? '밀집도' : '혼잡 지수'}</span>`
      + steps.map((s, i) => `<span class="chip"><i style="background:${s.color}"></i>${i === 0 ? '<1' : s.min + (i === steps.length - 1 ? '+' : '')}</span>`).join('')
      + '<span class="unit">%</span></span>';
  } else {
    box.innerHTML = now
      ? '<span class="unit">숫자 = 해역별 선박 수 · 진한 칸 = 상위 3곳 · 누르면 칸별로</span>'
      : '<span class="unit">숫자 = 해역별 혼잡 칸 수(보통 이상) · 진한 칸 = 상위 3곳 · 누르면 칸별로</span>';
  }
}

// 숫자 요약 (지금 = 선박, 미래 = 혼잡 예보)
function renderTrafficPanel() {
  const set = (id, text) => { document.getElementById(id).textContent = text; };
  if (currentWhen().now) {
    const d = trafficData || {};
    set('tr-time', d.regDt ? d.regDt.slice(5, 16) + ' 기준' : '-');
    set('tr-k1', '선박 수');
    set('tr-k2', '선박 있는 칸');
    set('tr-top-k', '가장 붐비는 칸');
    set('tr-ships', d.totalShips != null ? trFmt(d.totalShips) + '척' : '-');
    set('tr-cells', d.shipCells != null ? trFmt(d.shipCells) + '칸' : '-');
    set('tr-top-n', d.top ? trFmt(d.top.vmtc) + '척' : '-');
    set('tr-top-s', d.top ? (d.top.near ?? '') + ' · 밀집도 ' + (d.top.dnsty ?? '-') + '%' : '');
    set('tr-msg', d.error ?? '');
  } else {
    const d = trafficForecast || {};
    const c = d.counts || {};
    set('tr-time', whenLabel() + ' 혼잡 예보');
    set('tr-k1', '혼잡 칸 (보통 이상)');
    set('tr-k2', '높음 · 매우높음');
    set('tr-top-k', '가장 혼잡한 칸');
    set('tr-ships', d.counts ? trFmt((c['보통'] || 0) + (c['높음'] || 0) + (c['매우높음'] || 0)) + '칸' : '-');
    set('tr-cells', d.counts ? `${trFmt(c['높음'] || 0)} · ${trFmt(c['매우높음'] || 0)}칸` : '-');
    set('tr-top-n', d.top ? `${d.top.level} · 지수 ${d.top.index}%` : '-');
    set('tr-top-s', d.top ? (d.top.near ?? '') : '');
    set('tr-msg', d.error ?? '');
  }
}

// 지금 보는 시각 기준으로 다시 그리고 안전진단에 알림
function refreshTraffic() {
  rebuildTraffic();
  renderTrafficPanel();
  drawTraffic();
  drawLegend();
  document.dispatchEvent(new CustomEvent('traffic-updated'));  // 안전진단 결합 숫자 다시 계산
}

// 실시간 (1분마다 서버에 새 스냅샷이 있는지 확인 — MTIS 호출 아님)
function loadTraffic() {
  axios.get('/api/traffic/realtime')
    .then(response => {
      trafficData = response.data;
      if (currentWhen().now) refreshTraffic();
    })
    .catch(err => {
      if (currentWhen().now) document.getElementById('tr-msg').textContent = '교통 자료를 불러오지 못했습니다';
      console.log('교통 자료를 불러오지 못했습니다.', err);
    });
}

// 혼잡 예보 (고른 시각, 처음 한 번은 서버가 MTIS 를 부름)
function loadTrafficForecast() {
  const w = currentWhen();
  if (w.now) return;
  trafficForecast = null;
  refreshTraffic();
  axios.get('/api/traffic/forecast', { params: { date: w.date, time: w.hour } })
    .then(response => {
      const w2 = currentWhen();
      if (w2.now || w2.date !== w.date || w2.hour !== w.hour) return;  // 그사이 시각을 바꿨으면 버림
      trafficForecast = response.data;
      refreshTraffic();
    })
    .catch(err => {
      document.getElementById('tr-msg').textContent = '혼잡 예보를 불러오지 못했습니다';
      console.log('혼잡 예보를 불러오지 못했습니다.', err);
    });
}

document.getElementById('traffic-panel').classList.toggle('collapsed', !trafficOn());


// 켜기 / 끄기 — 끄면 패널이 한 줄로 접히고 지도에서 지움
document.getElementById('tr-on').addEventListener('change', () => {
  document.getElementById('traffic-panel').classList.toggle('collapsed', !trafficOn());
  drawTraffic();
  drawLegend();
});

// 볼 시각이 바뀌면
document.addEventListener('when-changed', () => {
  if (currentWhen().now) refreshTraffic(); else loadTrafficForecast();
});

// 줌에 따라 해역 ↔ 칸별로 바뀌고, 칸별은 화면 안 칸만 그리므로 다시 그림
map.on('zoomend moveend', () => {
  if (trafficOn()) {
    drawTraffic();
    drawLegend();
  }
});

// 가장 붐비는(혼잡한) 칸으로 이동
document.getElementById('tr-top').addEventListener('click', () => {
  const d = currentWhen().now ? trafficData : trafficForecast;
  if (d && d.top) map.flyTo([d.top.lat, d.top.lng], DETAIL_ZOOM);
});

drawLegend();
loadTraffic();
setInterval(loadTraffic, 60 * 1000);
