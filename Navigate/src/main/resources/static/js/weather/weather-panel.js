/**
 * 기상 패널 (사이드바 오른쪽에 붙어서 튀어나오는 별도 패널)
 * - 지도에서 고른 출발지/도착지(sessionStorage: startPort, endPort)의 현재 날씨를 보여줌
 * - 사이드바(.side-div) 안에는 아무것도 넣지 않음: body에 따로 붙이고 위치만 사이드바 오른쪽 끝에 맞춤
 * - side.js / map.js 코드는 건드리지 않음: sessionStorage 값을 주기적으로 읽어서 바뀌면 다시 그림
 * - 데이터: GET /api/weather/ports/weather (서버가 30분마다 갱신한 캐시)
 * - 72시간 예보 그래프는 화면 아래에서 올라오는 바(지도 폭)로 크게 보여줌
 */
(function () {
  'use strict';
  if (window.__weatherPanelLoaded) return;
  window.__weatherPanelLoaded = true;

  const API = '/api/weather/ports/weather';
  const RELOAD_MS = 5 * 60 * 1000;   // 서버 캐시 다시 읽는 주기
  const WATCH_MS = 300;              // 출발/도착 선택 변화 감지 주기

  // 판정 기준 (서버 PortWeather.level과 동일)
  const DANGER = { wave: 3.0, wind: 14.0, vis: 1.0 };
  const CAUTION = { wave: 2.0, wind: 10.0, vis: 3.0 };
  const RANK = { 안전: 0, 미상: 1, 주의: 2, 위험: 3 };
  const COLORS = { 안전: '#2e7d32', 주의: '#ef8f00', 위험: '#c62828', 미상: '#8a8a8a' };

  const DIRS = ['북', '북북동', '북동', '동북동', '동', '동남동', '남동', '남남동',
    '남', '남남서', '남서', '서남서', '서', '서북서', '북서', '북북서'];

  let byName = new Map();
  let refreshedAt = null;
  let loadError = null;
  let lastKey = null;
  const forecasts = new Map();   // 항구명(norm) → { at, data }
  const FORECAST_TTL = 10 * 60 * 1000;

  const norm = (s) => String(s || '').replace(/[\s·ㆍ.\-]/g, '').replace(/항$/, '');
  const has = (v) => v !== null && v !== undefined;
  const fmt = (v, unit, d = 1) => (has(v) ? `${Number(v).toFixed(d)}${unit}` : '-');
  const dirName = (deg) => (has(deg) ? DIRS[Math.round(deg / 22.5) % 16] : '');
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  /** 주의/위험이 된 이유 */
  function reasons(w) {
    const out = [];
    if (has(w.waveHeightM) && w.waveHeightM >= CAUTION.wave) out.push(`파고 ${fmt(w.waveHeightM, 'm')}`);
    if (has(w.windSpeedMs) && w.windSpeedMs >= CAUTION.wind) out.push(`풍속 ${fmt(w.windSpeedMs, 'm/s')}`);
    if (has(w.visibilityKm) && w.visibilityKm <= CAUTION.vis) out.push(`시정 ${fmt(w.visibilityKm, 'km')}`);
    return out;
  }

  // ---------- 화면 ----------
  function ensurePanel() {
    let panel = document.getElementById('wx-panel');
    if (panel) return panel;
    if (!document.querySelector('.side-div')) return null;

    panel = document.createElement('aside');
    panel.id = 'wx-panel';
    panel.className = 'wx-panel wx-closed';
    panel.innerHTML = `
      <button type="button" class="wx-tab" title="기상 패널 열기/닫기">
        <span class="wx-tab-dot"></span><span class="wx-tab-text">기상</span><span class="wx-tab-arrow">▶</span>
      </button>
      <div class="wx-body">
        <div class="wx-head">
          <span class="wx-title">항구 기상</span>
          <span class="wx-time"></span>
          <button type="button" class="wx-close" title="닫기">✕</button>
        </div>
        <div class="wx-summary"></div>
        <div class="wx-cards"></div>
        <button type="button" class="cctv-open-btn">항만 영상</button>
        <div class="wx-foot">위험: 파고 3m↑ · 풍속 14m/s↑ · 시정 1km↓<br>주의: 파고 2m↑ · 풍속 10m/s↑ · 시정 3km↓</div>
      </div>`;
    document.body.appendChild(panel);

    panel.querySelector('.wx-tab').addEventListener('click', () => setOpen(panel.classList.contains('wx-closed')));
    panel.querySelector('.wx-close').addEventListener('click', () => setOpen(false));
    panel.addEventListener('click', (e) => {
      if (e.target.closest('[data-wx-open-drawer]')) setDrawer(true);
    });
    ensureDrawer();

    place();
    window.addEventListener('resize', () => { place(); if (drawerOpen) renderDrawer(); });
    window.addEventListener('scroll', place, { passive: true });
    if (window.ResizeObserver) {
      const ro = new ResizeObserver(() => place());
      ro.observe(document.querySelector('.side-div'));
      const mapDiv = document.querySelector('.map-div');
      if (mapDiv) ro.observe(mapDiv);
    }
    return panel;
  }

  /** 사이드바 오른쪽 끝에 붙임 (레이아웃 CSS는 건드리지 않고 위치만 계산) */
  function place() {
    placeDrawer();
    const panel = document.getElementById('wx-panel');
    const side = document.querySelector('.side-div');
    if (!panel || !side) return;
    const r = side.getBoundingClientRect();
    const top = Math.max(r.top, 0);
    const bottom = r.bottom;
    panel.style.left = `${Math.round(r.right)}px`;
    panel.style.top = `${Math.round(top)}px`;
    panel.style.height = `${Math.max(Math.round(bottom - top), 120)}px`;
  }

  function setOpen(open) {
    const panel = document.getElementById('wx-panel');
    if (!panel) return;
    panel.classList.toggle('wx-closed', !open);
    panel.querySelector('.wx-tab-arrow').textContent = open ? '◀' : '▶';
    placeDrawer();
  }


  // ---------- 72시간 예보 ----------
  function forecastOf(name) {
    const f = forecasts.get(norm(name));
    return f && f.data ? f.data : null;
  }

  async function loadForecast(name) {
    if (!name) return;
    const key = norm(name);
    const cached = forecasts.get(key);
    if (cached && (cached.loading || Date.now() - cached.at < FORECAST_TTL)) return;
    forecasts.set(key, { at: Date.now(), loading: true, data: cached ? cached.data : null });
    try {
      const res = await fetch(`/api/weather/ports/${encodeURIComponent(name)}/forecast`, { headers: { Accept: 'application/json' } });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      forecasts.set(key, { at: Date.now(), loading: false, data: await res.json() });
    } catch (e) {
      forecasts.set(key, { at: Date.now(), loading: false, data: cached ? cached.data : null, error: e.message });
      console.warn('[weather] 예보를 불러오지 못했습니다:', name, e.message);
    }
    render();
  }

  const hh = (t) => `${Number(t.slice(5, 7))}/${Number(t.slice(8, 10))} ${t.slice(11, 13)}시`;

  /** "72시간 내 최대 파고 2.8m (10/22 03시) · 주의 이상 9시간, 10/21 15시부터" */
  function forecastSummary(hours) {
    const waves = hours.filter((h) => has(h.waveHeightM));
    const peak = waves.length ? waves.reduce((a, b) => (b.waveHeightM > a.waveHeightM ? b : a)) : null;
    const bad = hours.filter((h) => RANK[h.level] >= RANK['주의']);
    const danger = hours.filter((h) => h.level === '위험');
    const parts = [];
    if (peak) parts.push(`최대 파고 ${fmt(peak.waveHeightM, 'm')} (${hh(peak.time)})`);
    if (danger.length) parts.push(`<b class="wx-fc-bad" style="--lv:${COLORS['위험']}">위험 ${danger.length}시간 · ${hh(danger[0].time)}부터</b>`);
    else if (bad.length) parts.push(`<b class="wx-fc-bad" style="--lv:${COLORS['주의']}">주의 ${bad.length}시간 · ${hh(bad[0].time)}부터</b>`);
    else parts.push('72시간 동안 주의 기준 이하');
    return parts.join('<br>');
  }

  function forecastBlock(name) {
    const f = forecastOf(name);
    const st = forecasts.get(norm(name));
    let body;
    if (!f) body = `<div class="wx-fc-none">${st && st.error ? '예보를 불러오지 못했습니다' : '예보 불러오는 중…'}</div>`;
    else if (!(f.hours || []).length) body = '<div class="wx-fc-none">예보 없음</div>';
    else body = `<div class="wx-fc-sum">${forecastSummary(f.hours)}</div>`;
    return `
      <div class="wx-fc">
        <div class="wx-fc-title">향후 72시간 ${f && f.stale ? '<span class="wx-fc-stale">갱신 실패 · 이전 예보</span>' : ''}</div>
        ${body}
        <button type="button" class="wx-fc-open" data-wx-open-drawer>그래프 크게 보기 ▲</button>
      </div>`;
  }

  function card(role, name) {
    const label = role === 'start' ? '출발' : '도착';
    if (!name) {
      return `<div class="wx-card wx-empty"><span class="wx-role">${label}</span>
                지도에서 ${label === '출발' ? '출발지' : '도착지'}를 선택하세요</div>`;
    }
    const w = byName.get(norm(name));
    if (!w) {
      const msg = loadError ? '기상 데이터를 불러오지 못했습니다' : (refreshedAt ? '이 항구의 기상 정보가 없습니다' : '불러오는 중…');
      return `<div class="wx-card wx-empty"><span class="wx-role">${label}</span><b>${esc(name)}</b><br>${msg}</div>`;
    }
    const lv = w.level || '미상';
    const why = reasons(w);
    return `
      <div class="wx-card" style="--lv:${COLORS[lv]}">
        <div class="wx-card-head">
          <span class="wx-role">${label}</span>
          <b class="wx-name">${esc(w.portName)}</b>
          <span class="wx-badge">${lv}</span>
        </div>
        ${why.length ? `<div class="wx-why">${why.join(' · ')}</div>` : ''}
        ${w.stale ? `<div class="wx-stale">최신 갱신 실패 · 이전 값</div>` : ''}
        <dl class="wx-grid">
          <dt>풍속</dt><dd>${fmt(w.windSpeedMs, ' m/s')} <small>${dirName(w.windDirDeg)}</small></dd>
          <dt>돌풍</dt><dd>${fmt(w.windGustMs, ' m/s')}</dd>
          <dt>파고</dt><dd>${fmt(w.waveHeightM, ' m')}</dd>
          <dt>파주기</dt><dd>${fmt(w.wavePeriodS, ' s')}</dd>
          <dt>시정</dt><dd>${fmt(w.visibilityKm, ' km')}</dd>
          <dt>기온</dt><dd>${fmt(w.tempC, '℃')}</dd>
          <dt>수온</dt><dd>${fmt(w.seaTempC, '℃')}</dd>
          <dt>강수</dt><dd>${fmt(w.precipMm, ' mm')}</dd>
        </dl>
        ${forecastBlock(w.portName)}
      </div>`;
  }

  function summary(start, end) {
    const list = [['출발지', start], ['도착지', end]]
      .filter(([, n]) => n)
      .map(([role, n]) => ({ role, w: byName.get(norm(n)) }))
      .filter((x) => x.w);
    if (!list.length) return { text: '', lv: null };
    const worst = list.reduce((a, b) => (RANK[b.w.level] > RANK[a.w.level] ? b : a));
    const lv = worst.w.level || '미상';
    // 지금은 괜찮아도 72시간 안에 나빠지는 경우 알려줌
    const ahead = list
      .map((x) => ({ role: x.role, f: forecastOf(x.w.portName) }))
      .filter((x) => x.f && x.f.hours)
      .map((x) => ({ role: x.role, worst: x.f.hours.reduce((a, h) => Math.max(a, RANK[h.level] ?? 0), 0) }))
      .filter((x) => x.worst >= RANK['주의']);
    const aheadText = ahead.length
      ? ` · 72시간 내 ${ahead.map((x) => `${x.role} ${x.worst === RANK['위험'] ? '위험' : '주의'}`).join(', ')} 예보`
      : '';
    if (lv === '안전') {
      const base = list.length === 2 ? '출발지·도착지 모두 운항 양호' : `${list[0].role} 운항 양호`;
      return { text: base + aheadText, lv: ahead.length ? (ahead.some((x) => x.worst === RANK['위험']) ? '위험' : '주의') : lv };
    }
    if (lv === '미상') return { text: '일부 기상 정보 없음', lv };
    const why = reasons(worst.w);
    return { text: `${worst.role} ${lv}${why.length ? ' · ' + why.join(', ') : ''}${aheadText}`, lv };
  }

  function render() {
    const panel = ensurePanel();
    if (!panel) return;
    const start = sessionStorage.getItem('startPort');
    const end = sessionStorage.getItem('endPort');
    loadForecast(start);
    loadForecast(end);
    panel.querySelector('.wx-cards').innerHTML = card('start', start) + card('end', end);
    const s = summary(start, end);
    const sumEl = panel.querySelector('.wx-summary');
    sumEl.textContent = s.text;
    sumEl.style.display = s.text ? '' : 'none';
    sumEl.style.setProperty('--lv', s.lv ? COLORS[s.lv] : '#888');
    panel.querySelector('.wx-time').textContent =
      refreshedAt && refreshedAt.length > 15 ? `${refreshedAt.slice(11, 16)} 갱신` : '';
    const dot = panel.querySelector('.wx-tab-dot');
    dot.style.background = s.lv ? COLORS[s.lv] : 'transparent';
    renderDrawer();
  }


  // ---------- 아래에서 올라오는 72시간 그래프 바 ----------
  const DRAWER_H = 250;
  const SERIES = [
    { role: 'start', label: '출발', color: '#1f6fd1', dash: '' },
    { role: 'end', label: '도착', color: '#8a3ffc', dash: '6 4' },
  ];
  let drawerOpen = false;
  let drawerData = [];   // [{ label, color, dash, name, hours }]

  function ensureDrawer() {
    let d = document.getElementById('wx-drawer');
    if (d) return d;
    d = document.createElement('section');
    d.id = 'wx-drawer';
    d.className = 'wx-drawer';
    d.innerHTML = `
      <button type="button" class="wx-drawer-handle">
        <span class="wx-tab-dot"></span>향후 72시간 파고·풍속 <span class="wx-drawer-arrow">▲</span>
      </button>
      <div class="wx-drawer-body">
        <div class="wx-drawer-head">
          <div class="wx-drawer-legend"></div>
          <button type="button" class="wx-close" title="닫기">✕</button>
        </div>
        <div class="wx-drawer-charts">
          <div class="wx-chart" data-kind="wave"></div>
          <div class="wx-chart" data-kind="wind"></div>
        </div>
        <div class="wx-drawer-tip"></div>
      </div>`;
    document.body.appendChild(d);
    d.querySelector('.wx-drawer-handle').addEventListener('click', () => setDrawer(!drawerOpen));
    d.querySelector('.wx-drawer-body .wx-close').addEventListener('click', () => setDrawer(false));
    d.querySelector('.wx-drawer-charts').addEventListener('mousemove', onChartHover);
    d.querySelector('.wx-drawer-charts').addEventListener('mouseleave', hideTip);
    return d;
  }

  function setDrawer(open) {
    drawerOpen = open;
    const d = ensureDrawer();
    d.classList.toggle('wx-drawer-open', open);
    d.querySelector('.wx-drawer-arrow').textContent = open ? '▼' : '▲';
    place();
    if (open) renderDrawer();
  }

  /**
   * 위치:
   * - 왼쪽: 사이드바 오른쪽 끝, 기상 패널이 펼쳐져 있으면 패널 오른쪽 끝
   * - 오른쪽: 지도 영역 오른쪽 끝
   * - 아래: 화면(브라우저 창) 맨 아래
   */
  let lastDrawerWidth = 0;
  function placeDrawer() {
    const d = document.getElementById('wx-drawer');
    const mapDiv = document.querySelector('.map-div') || document.getElementById('map');
    if (!d || !mapDiv) return;
    const r = mapDiv.getBoundingClientRect();
    let left = r.left;
    const panel = document.getElementById('wx-panel');
    if (panel && !panel.classList.contains('wx-closed')) {
      const body = panel.querySelector('.wx-body');
      if (body) left = Math.max(left, body.getBoundingClientRect().right);
    }
    const width = Math.max(r.right - left, 200);
    d.style.left = `${Math.round(left)}px`;
    d.style.width = `${Math.round(width)}px`;
    d.style.bottom = '0px';
    const body = d.querySelector('.wx-drawer-body');
    // 화면 높이의 60%를 넘지 않게
    if (body) body.style.height = `${Math.round(Math.min(DRAWER_H, window.innerHeight * 0.6))}px`;
    if (drawerOpen && Math.abs(width - lastDrawerWidth) > 4) {
      lastDrawerWidth = width;
      requestAnimationFrame(renderDrawer);   // 폭이 바뀌면 그래프 다시 그림
    }
  }

  function collectDrawerData() {
    const names = { start: sessionStorage.getItem('startPort'), end: sessionStorage.getItem('endPort') };
    return SERIES.filter((s) => names[s.role]).map((s) => {
      const f = forecastOf(names[s.role]);
      return { ...s, name: (f && f.portName) || names[s.role], hours: (f && f.hours) || [] };
    });
  }

  function niceMax(v) {
    const steps = [1, 2, 2.5, 5, 10];
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    for (const s of steps) if (s * p >= v) return s * p;
    return 10 * p;
  }

  /** 꺾은선 그래프 (출발/도착 두 줄 겹쳐 그림, 주의·위험 구간은 배경색) */
  function lineChart(el, kind, data) {
    const W = Math.max(el.clientWidth, 200);
    const H = Math.max((el.clientHeight || 190) - 18, 120);   // 제목 높이 제외
    const pad = { l: 38, r: 10, t: 18, b: 30 };
    const key = kind === 'wave' ? 'waveHeightM' : 'windSpeedMs';
    const unit = kind === 'wave' ? 'm' : 'm/s';
    const caution = kind === 'wave' ? CAUTION.wave : CAUTION.wind;
    const danger = kind === 'wave' ? DANGER.wave : DANGER.wind;
    const base = data.find((d) => d.hours.length);
    if (!base) {
      el.innerHTML = `<div class="wx-chart-title">${kind === 'wave' ? '파고' : '풍속'}</div><div class="wx-fc-none">예보 없음</div>`;
      return;
    }
    const hours = base.hours;
    const n = hours.length;
    const vals = data.flatMap((d) => d.hours.map((h) => h[key])).filter(has);
    const maxV = niceMax(Math.max(danger * 1.25, ...(vals.length ? vals : [0])));
    const x = (i) => pad.l + ((W - pad.l - pad.r) * i) / Math.max(n - 1, 1);
    const y = (v) => pad.t + (H - pad.t - pad.b) * (1 - v / maxV);

    let g = '';
    // 배경 구간
    g += `<rect x="${pad.l}" y="${y(maxV)}" width="${W - pad.l - pad.r}" height="${y(danger) - y(maxV)}" fill="${COLORS['위험']}" opacity=".08"/>`;
    g += `<rect x="${pad.l}" y="${y(danger)}" width="${W - pad.l - pad.r}" height="${y(caution) - y(danger)}" fill="${COLORS['주의']}" opacity=".09"/>`;
    // y축 눈금
    const ticks = [0, caution, danger, maxV].filter((v, i, a) => a.indexOf(v) === i);
    ticks.forEach((v) => {
      g += `<line x1="${pad.l}" x2="${W - pad.r}" y1="${y(v)}" y2="${y(v)}" class="wx-grid-line${v === caution || v === danger ? ' wx-grid-th' : ''}"/>`;
      g += `<text x="${pad.l - 6}" y="${y(v) + 4}" text-anchor="end" class="wx-axis">${v}</text>`;
    });
    g += `<text x="${W - pad.r}" y="${y(danger) - 4}" text-anchor="end" class="wx-th-label" fill="${COLORS['위험']}">위험 ${danger}${unit}</text>`;
    g += `<text x="${W - pad.r}" y="${y(caution) - 4}" text-anchor="end" class="wx-th-label" fill="${COLORS['주의']}">주의 ${caution}${unit}</text>`;
    // x축: 6시간마다 시각, 자정마다 날짜
    hours.forEach((h, i) => {
      const hour = Number(h.time.slice(11, 13));
      if (hour === 0) {
        g += `<line x1="${x(i)}" x2="${x(i)}" y1="${pad.t}" y2="${H - pad.b}" class="wx-day-line"/>`;
        g += `<text x="${x(i)}" y="${H - 4}" text-anchor="middle" class="wx-axis wx-axis-day">${Number(h.time.slice(5, 7))}/${Number(h.time.slice(8, 10))}</text>`;
      } else if (hour % 6 === 0) {
        g += `<text x="${x(i)}" y="${H - pad.b + 13}" text-anchor="middle" class="wx-axis">${hour}시</text>`;
      }
    });
    // 선
    data.forEach((d) => {
      let path = '';
      let pen = false;
      d.hours.forEach((h, i) => {
        const v = h[key];
        if (!has(v)) { pen = false; return; }
        path += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`;
        pen = true;
      });
      if (path) g += `<path d="${path}" fill="none" stroke="${d.color}" stroke-width="2.2" stroke-dasharray="${d.dash}" stroke-linejoin="round"/>`;
    });
    g += `<line class="wx-cursor" x1="0" x2="0" y1="${pad.t}" y2="${H - pad.b}" style="display:none"/>`;

    el.innerHTML = `<div class="wx-chart-title">${kind === 'wave' ? '파고 (m)' : '풍속 (m/s)'}</div>
      <svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${g}</svg>`;
    el.dataset.n = n;
    el.dataset.l = pad.l;
    el.dataset.r = pad.r;
  }

  function renderDrawer() {
    const d = ensureDrawer();
    drawerData = collectDrawerData();
    // 손잡이 점 색: 예보 중 가장 나쁜 등급
    const worst = drawerData.flatMap((s) => s.hours).reduce((a, h) => Math.max(a, RANK[h.level] ?? 0), -1);
    const lv = Object.keys(RANK).find((k) => RANK[k] === worst);
    d.querySelector('.wx-drawer-handle .wx-tab-dot').style.background = lv ? COLORS[lv] : 'transparent';
    d.querySelector('.wx-drawer-legend').innerHTML = drawerData.length
      ? drawerData.map((s) => `<span class="wx-legend-item"><svg width="26" height="8"><line x1="0" x2="26" y1="4" y2="4" stroke="${s.color}" stroke-width="2.2" stroke-dasharray="${s.dash}"/></svg>${s.label} ${esc(s.name)}</span>`).join('')
      : '<span class="wx-fc-none">지도에서 출발지/도착지를 선택하세요</span>';
    if (!drawerOpen) return;
    d.querySelectorAll('.wx-chart').forEach((el) => {
      if (drawerData.length) lineChart(el, el.dataset.kind, drawerData);
      else el.innerHTML = '';
    });
  }

  function onChartHover(e) {
    const chart = e.target.closest('.wx-chart');
    if (!chart || !chart.dataset.n || !drawerData.length) return;
    const svg = chart.querySelector('svg');
    const rect = svg.getBoundingClientRect();
    const n = Number(chart.dataset.n);
    const l = Number(chart.dataset.l);
    const r = Number(chart.dataset.r);
    const px = e.clientX - rect.left;
    const i = Math.round(((px - l) / (rect.width - l - r)) * (n - 1));
    if (i < 0 || i >= n) { hideTip(); return; }
    const cx = l + ((rect.width - l - r) * i) / Math.max(n - 1, 1);
    document.querySelectorAll('#wx-drawer .wx-cursor').forEach((c) => {
      c.setAttribute('x1', cx); c.setAttribute('x2', cx); c.style.display = '';
    });
    const base = drawerData.find((s) => s.hours[i]);
    const tip = document.querySelector('#wx-drawer .wx-drawer-tip');
    tip.innerHTML = `<b>${base ? hh(base.hours[i].time) : ''}</b>` + drawerData.map((s) => {
      const h = s.hours[i];
      if (!h) return '';
      return `<div><i style="background:${s.color}"></i>${s.label} ${esc(s.name)}
        <span class="wx-tip-lv" style="color:${COLORS[h.level] || '#888'}">${h.level}</span><br>
        파고 ${fmt(h.waveHeightM, 'm')} · 풍속 ${fmt(h.windSpeedMs, 'm/s')} · 돌풍 ${fmt(h.windGustMs, 'm/s')} · 시정 ${fmt(h.visibilityKm, 'km')}</div>`;
    }).join('');
    tip.style.display = 'block';
    const body = document.querySelector('#wx-drawer .wx-drawer-body').getBoundingClientRect();
    let left = e.clientX - body.left + 14;
    if (left + tip.offsetWidth > body.width - 8) left = e.clientX - body.left - tip.offsetWidth - 14;
    tip.style.left = `${left}px`;
  }

  function hideTip() {
    document.querySelectorAll('#wx-drawer .wx-cursor').forEach((c) => { c.style.display = 'none'; });
    const tip = document.querySelector('#wx-drawer .wx-drawer-tip');
    if (tip) tip.style.display = 'none';
  }

  // ---------- 데이터 ----------
  async function load() {
    try {
      const res = await fetch(API, { headers: { Accept: 'application/json' } });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      byName = new Map((data.ports || []).map((p) => [norm(p.portName), p]));
      refreshedAt = data.refreshedAt || null;
      loadError = null;
    } catch (e) {
      loadError = e.message;
      console.warn('[weather] 기상 데이터를 불러오지 못했습니다:', e.message);
    }
    render();
  }

  // 출발/도착 선택이 바뀌었는지 감시 (side.js 수정 없이 연동)
  function watch() {
    const key = (sessionStorage.getItem('startPort') || '') + '|' + (sessionStorage.getItem('endPort') || '');
    if (key !== lastKey) {
      const first = lastKey === null;
      lastKey = key;
      render();
      if (key === '|') {
        // 선택초기화(또는 아직 선택 전): 기상 패널·72시간 그래프 바를 닫고 숨김
        setOpen(false);
        setDrawer(false);
        setVisible(false);
      } else {
        setVisible(true);
        // 항구를 새로 고르면 패널을 자동으로 펼침 (처음 페이지 열 때는 접힌 상태 유지)
        if (!first) setOpen(true);
      }
    }
  }

  /** 항구를 하나도 고르지 않았을 때는 '기상' 탭과 그래프 손잡이까지 전부 숨김 */
  function setVisible(show) {
    ['wx-panel', 'wx-drawer'].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.style.display = show ? '' : 'none';
    });
    if (show) place();
  }

  // ---------- 시작 ----------
  function init() {
    if (!ensurePanel()) return;
    render();
    load();
    setInterval(load, RELOAD_MS);
    setInterval(watch, WATCH_MS);
    window.addEventListener('storage', watch);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
