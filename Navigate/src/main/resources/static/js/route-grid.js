// [개발 확인용] 바다 격자를 지도에 그려서 해안선과 맞는지 눈으로 확인한다.
//
// 경로가 육지를 피하려면 서버가 "이 칸이 바다인가"를 제대로 알고 있어야 한다.
// 그게 맞는지 확인하려고 만든 임시 화면이다. 완성되면 이 파일과
// side.html 의 체크박스, layout.html 의 script 줄을 지우면 된다.
//
// 켜는 법: 사이드바의 '바다격자(확인용)' 체크박스


// 격자 네모를 담아 둘 레이어
const gridLayer = L.layerGroup();

// 색깔
const GRID_COLOR_OK = '#2563eb';    // 파랑 — 배가 지날 수 있는 칸 (바다 30% 이상)
const GRID_COLOR_EDGE = '#f97316';  // 주황 — 바다가 조금 있지만 지날 수 없는 칸 (연안)


// 지금 지도에 보이는 범위의 격자를 서버에서 받아 그린다.
function loadGrid() {

  // 체크박스가 꺼져 있으면 그리지 않는다.
  if (!document.getElementById('grid-on').checked) {
    gridLayer.clearLayers();
    return;
  }

  // 너무 넓은 범위를 한 번에 받으면 칸이 수만 개가 되어 느려진다.
  // 줌 9 이상(=충분히 확대했을 때)만 그린다.
  if (map.getZoom() < 9) {
    gridLayer.clearLayers();
    showGridMessage('지도를 더 확대하면 격자가 보입니다');
    return;
  }

  // 지금 화면에 보이는 지도 범위
  const bounds = map.getBounds();

  axios.get('/api/route/grid', {
    params: {
      minLat: bounds.getSouth(),
      maxLat: bounds.getNorth(),
      minLng: bounds.getWest(),
      maxLng: bounds.getEast()
    }
  })
    .then(response => {
      drawGrid(response.data);
    })
    .catch(err => {
      console.log('격자를 불러오지 못했습니다.', err);
    });
}


// 받은 칸 목록을 네모로 그린다.
function drawGrid(data) {
  gridLayer.clearLayers();

  // 칸 크기의 절반. 칸 가운데 좌표에서 이만큼 더하고 빼면 네모가 된다.
  const half = data.cell / 2;

  data.cells.forEach(cell => {
    // [[남서쪽 위도, 경도], [북동쪽 위도, 경도]]
    const box = [
      [cell.lat - half, cell.lng - half],
      [cell.lat + half, cell.lng + half]
    ];

    L.rectangle(box, {
      color: cell.ok ? GRID_COLOR_OK : GRID_COLOR_EDGE,
      weight: 1,
      fillOpacity: 0.15 + cell.sea * 0.35   // 바다 비율이 높을수록 진하게
    })
      .bindTooltip('바다 ' + Math.round(cell.sea * 100) + '%' +
                   (cell.ok ? ' · 통과 가능' : ' · 통과 불가'))
      .addTo(gridLayer);
  });

  showGridMessage('격자 ' + data.cells.length + '칸');
}


// 사이드바에 안내 문구
function showGridMessage(text) {
  const box = document.getElementById('grid-info');
  if (box) box.textContent = text;
}


// ---------- 켜고 끄기 ----------

document.getElementById('grid-on').addEventListener('change', (e) => {
  if (e.target.checked) {
    gridLayer.addTo(map);
    loadGrid();
  } else {
    map.removeLayer(gridLayer);
    showGridMessage('');
  }
});

// 지도를 움직이거나 확대하면 그 범위의 격자를 다시 받는다.
map.on('moveend', loadGrid);
