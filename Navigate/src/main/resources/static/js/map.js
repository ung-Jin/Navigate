const map = L.map('map', {
  maxZoom: 10, //최대 확대 단계 10고정해야지 울산,부산 볼 수 있음
  //숫자 올리면 확대 더 가능함
  minZoom: 8,  //최소 축소 단계 다른나라 안보이게끔 조절
  maxBounds: [[32.6, 123.6], [38.9, 133]],  //우리나라 지도만 보이게끔 조절
  //[[남쪽 위도, 서쪽 경도], [북쪽 위도, 동쪽 경도]]
  maxBoundsViscosity: 1.0 //이거는 고정 나갓다돌아오는 뭐그런거?
}).setView([36.0, 127.5], 7); //첫 보이는 화면

// 기본 배경 지도
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

// 무역항 마커: /api/ports (DB의 ports 테이블)를 불러와서 항구마다 마커 + 이름표
// 항구 마커 모양: 이모지(⚓)를 아이콘으로 사용 (색, 크기는 layout.css의 .port-marker)
const portIcon = L.divIcon({
  className: 'port-marker',
  html: '⚓',
  iconSize: [24, 24],     // 아이콘 크기
  iconAnchor: [12, 12]    // 좌표에 맞출 아이콘 위치 (가운데)
});

const portMarkers = [];  // { name, marker } — 선택 표시를 바꾸려고 마커를 보관

// 선택한 출발지/도착지의 이름표에 'port-selected' 표시를 붙임 (색은 layout.css)
// side.js 의 renderRoute() 가 선택이 바뀔 때마다 호출함
function refreshPortLabels() {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');
  for (const p of portMarkers) {
    const label = p.marker.getTooltip().getElement();  // 이름표 HTML 요소
    label.classList.toggle('port-selected', p.name === start || p.name === end);
  }
}

axios.get('/api/ports')
  .then(response => {
    // axios는 응답 데이터가 res.data에 들어 있음
    for (const p of response.data) {
      // 마커나 이름표를 클릭하면 side.js의 clickPort()가 실행되어 sessionStorage에 저장됨
      const marker = L.marker([p.lat, p.lng], { icon: portIcon })
        .addTo(map)
        // interactive: true -> 이름표를 눌러도 마커를 누른 것과 같게 동작
        .bindTooltip(p.name, { permanent: true, direction: 'right', offset: [8, 0], interactive: true })
        .on('click', () => clickPort(p.name));
      portMarkers.push({ name: p.name, marker: marker });
    }
    refreshPortLabels();  // 이미 선택된 항구가 있으면 바로 표시
  });
