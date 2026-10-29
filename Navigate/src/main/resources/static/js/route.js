// 경로찾기: 서버(/api/route)에서 받은 경로를 지도에 선으로 그린다.
//
// map.js 다음에 불러와야 한다 (map 변수를 쓰기 때문).
// side.js 의 '경로찾기' 버튼이 이 파일의 requestRoute() 를 부른다.
//
// [1단계 - 지금] 서버가 직선 하나를 주고, 그걸 그대로 그린다. 육지를 뚫고 지나간다.
// [3단계 예정] 서버가 육지를 피해 꺾인 선을 주면, 이 파일은 고칠 것 없이 그대로 그려진다.


// 경로 선을 담아 둘 레이어. 새로 계산할 때마다 이 레이어만 비우면 이전 선이 지워진다.
const routeLayer = L.layerGroup().addTo(map);

// 선 색깔
const ROUTE_COLOR_SHORTEST = '#6b7280';  // 회색 — 최소경로
const ROUTE_COLOR_SAFE = '#2563eb';      // 파랑 — 안전경로


// 경로찾기 버튼을 눌렀을 때 실행된다. (side.js 가 부름)
// start, end 는 항구 이름 (예: '부산항')
function requestRoute(start, end) {

  // 이전에 그린 선을 지운다.
  routeLayer.clearLayers();
  showRouteMessage('경로를 계산하는 중...');

  // 서버에 경로를 물어본다.
  // params 에 넣으면 axios 가 알아서 /api/route?from=부산항&to=울산항 모양으로 만들어 준다.
  axios.get('/api/route', { params: { from: start, to: end } })
    .then(response => {
      const data = response.data;

      // 서버가 error 를 담아 보냈으면 메시지만 보여 주고 끝낸다.
      if (data.error) {
        showRouteMessage(data.error);
        return;
      }

      drawRoute(data);
    })
    .catch(err => {
      console.log('경로를 불러오지 못했습니다.', err);
      showRouteMessage('경로를 불러오지 못했습니다.');
    });
}


// 받은 경로 데이터를 지도에 그린다.
function drawRoute(data) {

  // ---------- 1. 최소경로 (회색 점선) ----------
  // coords 는 [[위도, 경도], [위도, 경도], ...] 모양이라 Leaflet 에 그대로 넣을 수 있다.
  L.polyline(data.shortest.coords, {
    color: ROUTE_COLOR_SHORTEST,
    weight: 4,
    dashArray: '8 6'   // 점선으로 (숫자는 '선 길이 빈칸 길이')
  }).addTo(routeLayer);

  // ---------- 2. 안전경로 (파란 실선) ----------
  // 1단계에서는 최소경로와 좌표가 같아서 선이 겹쳐 보인다.
  // 4단계에서 진짜 안전경로가 들어오면 다른 길로 갈라진다.
  L.polyline(data.safe.coords, {
    color: ROUTE_COLOR_SAFE,
    weight: 4
  }).addTo(routeLayer);

  // ---------- 3. 경로가 다 보이도록 지도 위치·배율 맞추기 ----------
  // L.latLngBounds 는 여러 점을 모두 포함하는 네모 범위를 만들어 준다.
  const bounds = L.latLngBounds(data.shortest.coords);
  map.fitBounds(bounds, { padding: [50, 50] });

  // ---------- 4. 사이드바에 숫자 보여 주기 ----------
  showRouteResult(data);
}


// 사이드바에 계산 결과(거리·시간)를 보여 준다.
function showRouteResult(data) {
  const box = document.getElementById('route-result');
  if (!box) return;

  box.innerHTML =
    '<div class="route-row">' +
      '<span class="route-label" style="color:' + ROUTE_COLOR_SHORTEST + '">최소경로</span>' +
      '<span>' + data.shortest.km + 'km · ' + data.shortest.hours + '시간</span>' +
    '</div>' +
    '<div class="route-row">' +
      '<span class="route-label" style="color:' + ROUTE_COLOR_SAFE + '">안전경로</span>' +
      '<span>' + data.safe.km + 'km · ' + data.safe.hours + '시간</span>' +
    '</div>' +
    '<div class="route-note">※ 1단계: 아직 직선입니다 (육지를 지나감)</div>';
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
