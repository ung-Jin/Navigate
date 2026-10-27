const map = L.map('map', {
  minZoom: 8,   // 이보다 축소하면 다른 나라까지 보임
  maxZoom: 10,  // 부산·울산 항만까지 볼 수 있는 확대
  maxBounds: [[32.6, 123.6], [38.9, 133]],  // 우리나라 바다만: [[남쪽 위도, 서쪽 경도], [북쪽 위도, 동쪽 경도]]
  maxBoundsViscosity: 1.0  // 1.0 = 범위 밖으로 끌어도 못 나감
}).setView([36.0, 127.5], 8);  // 첫 화면

// 기본 배경 지도
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

// 항구 마커: 흰 원 안의 닻 (원·색은 layout.css 의 .port-marker)
// 닻 그림: Tabler Icons 'anchor' (MIT). 선 색은 layout.css 의 color 를 따라감 (currentColor)
const portIcon = L.divIcon({
  className: 'port-marker',
  html: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v12m-8 -8a8 8 0 0 0 16 0m1 0h-2m-14 0h-2"/><path d="M12 6m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"/></svg>',
  // 원 24px - 테두리 2px x 2 = 안쪽 20px, 닻 16px -> 여백 2px (반 픽셀이 생기면 흐리고 한쪽으로 쏠림)
  iconSize: [24, 24],
  iconAnchor: [12, 12]  // 좌표에 원 가운데를 맞춤
});

// 이름표 자리: 첫 화면(줌 8·9)에서 이웃 항구와 겹치는 곳만 바꾸고, 나머지는 오른쪽 [10, 0]
// [방향, [가로, 세로]] — 10 = 마커에서 이름표까지 간격 (그 사이에 연결선, layout.css)
// 위·아래 이름표만 옆으로 비킴(±20). 옆 이름표는 양 끝이 둥글어서 위아래로 비키면 선이 곡선에 붙음
const labelPos = {
  '경인항': ['left', [-10, 0]],
  '인천항': ['bottom', [0, 10]],
  '대산항': ['top', [0, -10]],
  '태안항': ['left', [-10, 0]],
  '군산항': ['left', [-10, 0]],
  '하동항': ['top', [0, -10]],
  '광양항': ['left', [-10, 0]],
  '여수항': ['bottom', [0, 10]],
  '삼천포항': ['bottom', [0, 10]],
  '통영항': ['top', [-20, -10]],
  '마산항': ['left', [-10, 0]],
  '진해항': ['top', [20, -10]],
  '고현항': ['bottom', [0, 10]],
  '옥포항': ['top', [20, -10]],
  '옥계항': ['top', [0, -10]]
};

const portMarkers = [];  // { name, marker } — 선택 표시를 바꾸려고 보관

// 선택한 출발지/도착지의 마커와 이름표를 파랗게 ('port-selected', 색은 layout.css)
// side.js 의 renderRoute() 가 선택이 바뀔 때마다 부름
function refreshPortLabels() {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');
  for (const p of portMarkers) {
    const selected = p.name === start || p.name === end;
    p.marker.getElement().classList.toggle('port-selected', selected);
    p.marker.getTooltip().getElement().classList.toggle('port-selected', selected);
  }
}

// DB의 ports 테이블 -> 항구마다 마커 + 이름표
axios.get('/api/ports')
  .then(response => {
    for (const p of response.data) {
      const pos = labelPos[p.name] || ['right', [10, 0]];
      // 마커나 이름표를 누르면 side.js 의 clickPort() 가 sessionStorage 에 저장
      // interactive: true -> 이름표도 누를 수 있음, className -> 항구 이름표에만 layout.css 모양 적용
      const marker = L.marker([p.lat, p.lng], { icon: portIcon })
        .addTo(map)
        .bindTooltip(p.name, { permanent: true, direction: pos[0], offset: pos[1], interactive: true, className: 'port-label' })
        .on('click', () => clickPort(p.name));
      // 이름표를 옆으로 민 만큼 연결선을 반대로 옮겨서 마커 가운데를 가리키게 함 (layout.css 의 --line-x)
      marker.getTooltip().getElement().style.setProperty('--line-x', -pos[1][0] + 'px');
      portMarkers.push({ name: p.name, marker: marker });
    }
    refreshPortLabels();  // 이미 선택된 항구가 있으면 바로 표시
  });
