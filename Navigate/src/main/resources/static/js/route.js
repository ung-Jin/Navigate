// 경로찾기: 서버(/api/route)에서 받은 경로를 지도에 선으로 그린다.
//
// map.js 다음에 불러와야 한다 (map 변수를 쓰기 때문).
// side.js 의 '경로찾기' 버튼이 이 파일의 requestRoute() 를 부른다.
//
// 서버(A*)가 바다 칸만 밟은 좌표 목록을 주면 그대로 선으로 잇는다.
// 좌표가 2개든 20개든 그리는 코드는 같아서, 경로 계산 방식이 바뀌어도 이 파일은 고칠 것이 없다.


// 경로 선을 담아 둘 레이어. 새로 계산할 때마다 이 레이어만 비우면 이전 선이 지워진다.
const routeLayer = L.layerGroup().addTo(map);

// 경로선은 교통 칸(400)·위험 칸(420) 위, 해역 이름표(450)·항구 마커(600) 아래에 그린다.
// (안 그러면 위험 칸 아래로 선이 묻힘)
map.createPane('routePane').style.zIndex = 440;

// 선 색깔
const ROUTE_COLOR_SHORTEST = '#6b7280';  // 회색 — 최소경로
const ROUTE_COLOR_SAFE = '#2563eb';      // 파랑 — 안전경로


// 몇 번째 요청인지 세는 번호.
//
// 시각을 빠르게 바꾸면 요청이 여러 개 동시에 날아간다. 계산 시간이 저마다 달라서
// 먼저 보낸 요청이 나중에 도착하면 새 결과를 덮어써 버린다 (화면 라벨과 내용이 어긋남).
// 그래서 요청마다 번호를 매기고, 가장 마지막 요청의 응답만 화면에 그린다.
let routeRequestSeq = 0;


// 경로찾기 버튼을 눌렀을 때 실행된다. (side.js 가 부름)
// start, end 는 항구 이름 (예: '부산항')
function requestRoute(start, end) {

  const mySeq = ++routeRequestSeq;

  // 계산이 끝날 때까지 이전 경로를 흐리게 남겨 둔다.
  //
  // 선을 바로 지우면 몇 초 동안 화면이 비어서 "멈춘 것 같은" 느낌이 든다.
  // 이전 경로를 옅게 두면 실제 시간은 같아도 기다림이 훨씬 덜 느껴진다.
  routeLayer.eachLayer(l => l.setStyle && l.setStyle({ opacity: 0.25 }));
  showRouteMessage('경로를 계산하는 중...');

  // 사이드바에서 고른 '볼 시각' 을 함께 보낸다 (when.js).
  // 지금이면 실시간 교통을, 미래 시각이면 그 시각의 예보를 써서 위험도를 매긴다.
  const when = currentWhen();
  const params = { from: start, to: end };
  if (!when.now) {
    params.date = when.date;      // yyyyMMdd
    params.time = when.hour;      // HH
  }

  // 서버에 경로를 물어본다.
  // params 에 넣으면 axios 가 알아서 /api/route?from=부산항&to=울산항 모양으로 만들어 준다.
  axios.get('/api/route', { params })
    .then(response => {
      if (mySeq !== routeRequestSeq) {
        return;                   // 그 사이 더 새 요청이 나갔다 — 이 응답은 버린다
      }
      const data = response.data;

      // 서버가 error 를 담아 보냈으면 메시지만 보여 주고 끝낸다.
      if (data.error) {
        showRouteMessage(data.error);
        return;
      }

      // 이 결과가 어느 시각 기준인지도 함께 넘긴다.
      // 화면에 그릴 때 다시 읽으면, 그 사이 사용자가 시각을 바꿨을 때 어긋난다.
      drawRoute(data, when);
    })
    .catch(err => {
      if (mySeq !== routeRequestSeq) {
        return;
      }
      console.log('경로를 불러오지 못했습니다.', err);
      showRouteMessage('경로를 불러오지 못했습니다.');
    });
}


// 받은 경로 데이터를 지도에 그린다.
function drawRoute(data, when) {

  // 흐리게 남겨 뒀던 이전 경로를 이제 지운다.
  routeLayer.clearLayers();

  // ---------- 0. 흰 테두리 ----------
  // 두 경로 아래에 굵은 흰 선을 먼저 깔아서, 교통·위험 칸 색 위에서도 경로선이 또렷이 구분되게 한다.
  for (const path of [data.shortest, data.safe]) {
    L.polyline(path.coords, { pane: 'routePane', color: '#fff', weight: 8, opacity: 0.85, interactive: false }).addTo(routeLayer);
  }

  // ---------- 1. 최소경로 (회색 점선) ----------
  // coords 는 [[위도, 경도], [위도, 경도], ...] 모양이라 Leaflet 에 그대로 넣을 수 있다.
  L.polyline(data.shortest.coords, {
    pane: 'routePane',
    color: ROUTE_COLOR_SHORTEST,
    weight: 4,
    dashArray: '8 6'   // 점선으로 (숫자는 '선 길이 빈칸 길이')
  }).addTo(routeLayer);

  // ---------- 2. 안전경로 (파란 실선) ----------
  // 피할 위험이 없으면 최소경로와 좌표가 같아서 선이 겹쳐 보인다 (사이드바에 그렇다고 알린다).
  L.polyline(data.safe.coords, {
    pane: 'routePane',
    color: ROUTE_COLOR_SAFE,
    weight: 4
  }).addTo(routeLayer);

  // ---------- 3. 경로가 다 보이도록 지도 위치·배율 맞추기 ----------
  // L.latLngBounds 는 여러 점을 모두 포함하는 네모 범위를 만들어 준다.
  const bounds = L.latLngBounds(data.shortest.coords);
  map.fitBounds(bounds, { padding: [50, 50] });

  // ---------- 4. 사이드바에 숫자 보여 주기 ----------
  showRouteResult(data, when);
}


// 사이드바에 계산 결과(거리·시간·위험)를 보여 준다.
function showRouteResult(data, when) {
  const box = document.getElementById('route-result');
  if (!box) return;

  // 어느 시각 기준으로 계산한 경로인지
  let html = '<div class="route-when">기준 ' + whenLabel(when) + '</div>' +
    routeBlock('최소경로', ROUTE_COLOR_SHORTEST, data.shortest) +
    routeBlock('안전경로', ROUTE_COLOR_SAFE, data.safe);

  if (data.same) {
    // 피할 위험이 없으면 돌아갈 이유도 없다 — 알고리즘이 제대로 동작한 결과다
    html += '<div class="route-note">두 경로가 같습니다 — 우회할 위험 구간이 없습니다</div>';
  } else {
    const km = data.kmPct, ex = data.exposurePct;
    // 이 제품의 핵심은 위험이 얼마나 줄었는가: 위험을 앞에 둔다
    html += '<div class="route-diff">' +
      '위험 ' + (ex > 0 ? '+' : '') + ex + '% · ' +
      '거리 ' + (km > 0 ? '+' : '') + km + '%' +
    '</div>';
  }

  // 어떤 자료로 위험도를 매겼는지
  if (data.sources && data.sources.length) {
    // 자세한 자료 시각은 눌러야 보이게 접어 둔다 (원시 시각 글자가 결과를 덮지 않게)
    html += '<details class="route-note"><summary>경로 계산에 쓴 자료</summary>' + data.sources.join('<br>') + '</details>';
  }

  box.innerHTML = html;
}


// 경로 한 개의 숫자 묶음
function routeBlock(title, color, path) {
  let html =
    '<div class="route-row">' +
      '<span class="route-label" style="color:' + color + '">' + title + '</span>' +
      '<span>' + path.km + 'km · ' + path.hours + '시간</span>' +
    '</div>';

  // 위험 노출 = (위험도 × 지나간 거리) 의 합. 위험한 곳을 오래 지날수록 커진다.
  html += '<div class="route-sub">위험 노출 ' + path.exposure;
  if (path.highKm > 0) {
    html += ' · 고위험 ' + path.highKm + 'km';
  }
  html += '</div>';

  return html;
}


// 사이드바에 안내 문구 하나만 보여 준다. (계산 중 / 오류)
function showRouteMessage(text) {
  const box = document.getElementById('route-result');
  if (!box) return;
  box.innerHTML = '<div class="route-note">' + text + '</div>';
}


// 선택초기화 버튼을 누르면 그려 둔 경로도 같이 지운다.
document.getElementById('reset-btn').addEventListener('click', () => {
  routeLayer.clearLayers();
  const box = document.getElementById('route-result');
  if (box) box.innerHTML = '';
});


// '볼 시각'(지금 / 오늘·내일·모레 + 시각)을 바꾸면 그 시각 기준으로 경로를 다시 계산한다.
// when.js 가 바뀔 때마다 'when-changed' 이벤트를 보낸다.
//
// 날씨·교통은 시각마다 다르므로, 같은 두 항구라도 언제 떠나느냐에 따라 안전경로가 달라진다.
//
// 바로 보내지 않고 잠깐 기다리는 이유:
//   '내일' 을 고르고 이어서 '06시' 를 고르면 이벤트가 두 번 온다.
//   그때마다 요청을 보내면 서버가 두 번 계산하는데, 앞의 결과는 어차피 버려진다.
//   잠깐 기다렸다가 마지막 선택만 보내면 헛계산이 사라진다.
const WHEN_WAIT_MS = 350;
let whenTimer = null;

document.addEventListener('when-changed', () => {
  // 경로를 아직 한 번도 안 찾았으면 아무것도 하지 않는다.
  // (지도에 선이 있는지로 판단하면 안 된다 — 계산 중에는 흐려진 상태라 헷갈린다)
  if (routeRequestSeq === 0) return;

  clearTimeout(whenTimer);
  whenTimer = setTimeout(() => {
    const start = sessionStorage.getItem('startPort');
    const end = sessionStorage.getItem('endPort');
    if (start && end) {
      requestRoute(start, end);
    }
  }, WHEN_WAIT_MS);
});
