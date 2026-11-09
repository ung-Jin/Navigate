// 사고 다발: 사고다발 격자(MTIS #243) · 좌초 다발지점(MTIS #231) — 안전진단 아래 체크박스로 따로 켜고 끔
// 자료: /api/safety/hotspots (가장 최근 연도, 서버가 한 번 받으면 파일에 두고 계속 씀)
// map.js·traffic.js 다음에 불러와야 함 (map, portMarkers, hoverTip 사용)
//
// - 사고다발 격자 = 검은 표지 "▲사고 척수" (항구 이름표 배지와 같은 모양, 12px 굵은 글씨)
//     항구 ⚓ 와 화면에서 겹치는 격자(⚓ 에서 24px 안)는 가려지므로, 축소(배율 8·9)했을 때는 항구 이름표에 "▲합계" 배지로 붙임
//     배지에 마우스를 올리면 묶인 격자 목록(위치·사고 척수)을 정보 창으로 보여 줌
//     (km 가 아니라 화면 거리로 정함 — 배율 8 에서는 약 12km, 9 에서는 약 6km 안이 겹침)
//     가장 크게 확대(배율 10)하면 실제 위치에 표지
// - 좌초 다발지점 = 갈색 마름모 ◆. 축소(배율 8)했을 때는 사고 5건 이상 묶인 곳만
// - 색은 안전진단(노랑·주황·빨강)·교통현황(보라)과 겹치지 않게
// - 처음 체크할 때 한 번만 받아 두고, 끄고 켤 때는 다시 받지 않음

const HS_PORT_PX = 24;       // 항구 ⚓ 에서 화면상 이 거리(px) 안 격자는 축소 화면에서 항구 이름표 배지로
const HS_DETAIL_ZOOM = 10;   // 이 배율부터 항구 근처 격자도 실제 위치에 그림
const HS_ALL_STRAND_ZOOM = 9;  // 이 배율부터 좌초 다발지점을 전부 (그 아래는 5건 이상만)
const HS_STRAND_MIN = 5;

// 표지 층: 교통 칸 숫자(560) 위, 항구 ⚓(600)·이름표(650) 아래 — 겹치면 항상 항구가 위에 보이게
map.createPane('hotspotBadge').style.zIndex = 590;
const hsGridLayer = L.layerGroup().addTo(map);
const hsStrandLayer = L.layerGroup().addTo(map);
let hotspotData = null;
let hotspotLoading = null;

const hsGridOn = () => document.getElementById('hs-grid').checked;
const hsStrandOn = () => document.getElementById('hs-strand').checked;

// 격자 표지: "▲사고 척수" — 사고 40척 이상은 한 단계 크게
function hsGridIcon(count) {
  return L.divIcon({ className: 'hs-icon-wrap', iconSize: null,
                     html: `<div class="hs-gbadge${(count || 0) >= 40 ? ' big' : ''}">▲${count ?? ''}</div>` });
}

const hsDiamond = L.divIcon({ className: 'hs-icon-wrap', iconSize: null, html: '<div class="hs-dia"></div>' });

// 지금 배율에서 격자 표지가 겹칠 만큼 가까운 항구 (없으면 null) — 항구 목록은 map.js 의 portMarkers
function hsNearPort(g) {
  const at = map.latLngToContainerPoint([g.lat, g.lng]);
  let best = null;
  for (const p of portMarkers) {
    const px = at.distanceTo(map.latLngToContainerPoint(p.marker.getLatLng()));
    if (px <= HS_PORT_PX && (!best || px < best.px)) best = { p, px };
  }
  return best && best.p;
}

// 항구 이름 → 이름표 배지로 묶인 격자들 (배지 정보 창에서 씀)
let hsPortGroups = new Map();

// 항구 이름표 배지 붙이기 / 떼기 (이름표 글자는 map.js 가 넣은 항구 이름 그대로 두고 뒤에 덧붙임)
function hsSetPortBadges(byPort) {
  hsPortGroups = byPort;
  for (const p of portMarkers) {
    const tip = p.marker.getTooltip();
    const gs = byPort.get(p.name);
    if (!gs) {
      tip.setContent(p.name);
      continue;
    }
    const sum = gs.reduce((a, g) => a + (g.count || 0), 0);
    tip.setContent(`${p.name}<span class="hs-pbadge" data-port="${p.name}">▲${sum}</span>`);
  }
}

function drawHotspots() {
  hsGridLayer.clearLayers();
  hsStrandLayer.clearLayers();
  const byPort = new Map();  // 항구 이름 → 이름표 배지로 묶인 격자들
  const d = hotspotData;
  if (d && !d.error && hsGridOn()) {
    const detail = map.getZoom() >= HS_DETAIL_ZOOM;
    (d.grids || []).forEach(g => {
      const port = detail ? null : hsNearPort(g);
      if (port) {
        if (!byPort.has(port.name)) byPort.set(port.name, []);
        byPort.get(port.name).push(g);
        return;
      }
      hoverTip(L.marker([g.lat, g.lng], { icon: hsGridIcon(g.count), pane: 'hotspotBadge', keyboard: false, zIndexOffset: g.count || 0 }),
        () => `<b>${g.near ?? ''}</b><br>사고 다발 격자 (약 2.8km 칸)`
          + `<br>사고 <b>${g.count ?? '-'}척</b> · 연평균 ${g.avg ?? '-'}척`,
        { sticky: false, at: [g.lat, g.lng], direction: 'top', offset: [0, -14] })
        .addTo(hsGridLayer);
    });
  }
  hsSetPortBadges(byPort);

  if (d && !d.error && hsStrandOn()) {
    const all = map.getZoom() >= HS_ALL_STRAND_ZOOM;
    (d.stranding || []).filter(p => all || (p.size || 0) >= HS_STRAND_MIN).forEach(p => {
      hoverTip(L.marker([p.lat, p.lng], { icon: hsDiamond, pane: 'hotspotBadge', keyboard: false }),
        () => `<b>${p.near ?? ''}</b><br>좌초 다발 지점`
          + `<br>최근 5년 좌초·좌주 <b>${p.size ?? '-'}건</b>이 모인 곳`,
        { sticky: false, at: [p.lat, p.lng], direction: 'top', offset: [0, -8] })
        .addTo(hsStrandLayer);
    });
  }
}

function renderHotspotPanel() {
  const d = hotspotData || {};
  const ok = d.year && !d.error;
  document.getElementById('hs-msg').textContent = d.error ?? '';
}

// 처음 체크할 때 한 번 받음 (받는 중이면 그 결과를 기다림)
function loadHotspots() {
  if (hotspotData && !hotspotData.error) return Promise.resolve();
  if (!hotspotLoading) {
    document.getElementById('hs-msg').textContent = '';
    hotspotLoading = axios.get('/api/safety/hotspots')
      .then(response => { hotspotData = response.data; })
      .catch(err => {
        hotspotData = { error: '사고 다발 자료를 불러오지 못했습니다' };
        console.log('사고 다발 자료를 불러오지 못했습니다.', err);
      })
      .finally(() => { hotspotLoading = null; renderHotspotPanel(); });
  }
  return hotspotLoading;
}

['hs-grid', 'hs-strand'].forEach(id => {
  document.getElementById(id).addEventListener('change', () => {
    if (hsGridOn() || hsStrandOn()) loadHotspots().then(drawHotspots);
    else drawHotspots();
  });
});

// 배율이 바뀌면 다시 그림 (항구 배지 ↔ 실제 위치, 좌초 5건 이상 ↔ 전부) — 26 + 87개라 가벼움
map.on('zoomend', () => {
  if (hsGridOn() || hsStrandOn()) drawHotspots();
});

// ---------- 항구 이름표 배지 정보 창 ----------
// 배지는 map.js 가 만든 항구 이름표 안에 있어서 Leaflet 레이어가 아님 → 지도 화면에서 마우스 이벤트를 받아 처리
// 정보 창은 교통·안전진단과 같은 것(infoTip, 화면에 하나만 — traffic.js 의 openInfoTip/closeInfoTip)
const hsPortTip = L.tooltip({ pane: 'infoTip', direction: 'top', offset: [0, -10] });

function hsPortTipHtml(name) {
  const gs = (hsPortGroups.get(name) || []).slice().sort((a, b) => (b.count || 0) - (a.count || 0));
  const sum = gs.reduce((a, g) => a + (g.count || 0), 0);
  return `<b>${name} 근처 사고 다발 격자 ${gs.length}칸</b> · 사고 <b>${sum}척</b>`
    + gs.map(g => `<br>▲${g.count ?? '-'}척 · 연평균 ${g.avg ?? '-'}척 — ${g.near ?? ''}`).join('')
    + '<br><span class="muted">확대하면 칸마다 실제 위치에 표시</span>';
}

map.getContainer().addEventListener('mouseover', e => {
  const badge = e.target.closest && e.target.closest('.hs-pbadge');
  if (!badge || map.dragging.moving()) return;
  const r = badge.getBoundingClientRect();
  const box = map.getContainer().getBoundingClientRect();
  const at = map.containerPointToLatLng([r.left + r.width / 2 - box.left, r.top - box.top]);
  hsPortTip.setContent(hsPortTipHtml(badge.dataset.port));
  closeInfoTip();
  map.openTooltip(hsPortTip, at);
  openInfoTip = hsPortTip;
});
map.getContainer().addEventListener('mouseout', e => {
  const badge = e.target.closest && e.target.closest('.hs-pbadge');
  if (badge && !badge.contains(e.relatedTarget) && openInfoTip === hsPortTip) closeInfoTip();
});
