const map = L.map('map', {
  maxZoom: 10, //최대 확대 단계 10고정해야지 울산,부산 볼 수 있음
  //숫자 올리면 확대 더 가능함
  minZoom: 8,  //최소 축소 단계 다른나라 안보이게끔 조절
  maxBounds: [[33, 124], [38.9, 133]],  //우리나라 지도만 보이게끔 조절
  //[[남쪽 위도, 서쪽 경도], [북쪽 위도, 동쪽 경도]]
  maxBoundsViscosity: 1.0 //이거는 고정 나갓다돌아오는 뭐그런거?
}).setView([36.0, 127.5], 7); //첫 보이는 화면



// 기본 배경 지도
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

// 바다 정보(항로, 부표 등)를 위에 덧씌우는 레이어
L.tileLayer('https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png', {
  attribution: '&copy; OpenSeaMap contributors'
}).addTo(map);

// 무역항 마커: /api/ports (DB의 ports 테이블)를 불러와서 항구마다 마커 + 클릭 시 이름/위경도 팝업

// 항구 마커 모양: 이모지(⚓)를 아이콘으로 사용 (색, 크기는 layout.css의 .port-marker)
const portIcon = L.divIcon({
  className: 'port-marker',
  html: '⚓',
  iconSize: [24, 24],     // 아이콘 크기
  iconAnchor: [12, 12],   // 좌표에 맞출 아이콘 위치 (가운데)
  popupAnchor: [0, -12]   // 팝업이 뜨는 위치 (아이콘 위쪽)
});

axios.get('/api/ports')
  .then(response => {
    // axios는 응답 데이터가 res.data에 들어 있음
    response.data.forEach(p => {
      // 마커나 이름표를 클릭하면 side.js의 clickPort()가 실행되어 sessionStorage에 저장됨
      L.marker([p.lat, p.lng], { icon: portIcon })
        .addTo(map)
        // interactive: true -> 이름표를 눌러도 마커를 누른 것과 같게 동작
        .bindTooltip(p.name, { permanent: true, direction: 'right', offset: [8, 0], interactive: true })
        .on('click', () => clickPort(p.name));
    });
  })
  .catch(err => {
    console.log('항구 데이터를 불러오지 못했습니다.', err);
  });
