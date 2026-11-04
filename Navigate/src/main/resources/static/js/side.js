// 선택한 항구의 이름만 sessionStorage에 저장 
// 키 이름: startPort(출발지), endPort(도착지) 


if (performance.getEntriesByType('navigation')[0].type === 'reload') {
  sessionStorage.removeItem('startPort');
  sessionStorage.removeItem('endPort');
}

// 항구 클릭 (지도의 마커나 이름표를 누르면 map.js가 호출함)
//  - 이미 선택된 항구(출발지/도착지)를 또 누르면 그 선택을 취소
//  - 출발지가 비어 있으면 출발지, 도착지가 비어 있으면 도착지로 선택
//  - 둘 다 선택된 상태에서 다른 항구를 누르면 그 항구를 새 출발지로 하고 도착지는 비움
function clickPort(name) {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');

  if (name === start) {
    sessionStorage.removeItem('startPort');
  } else if (name === end) {
    sessionStorage.removeItem('endPort');
  } else if (!start) {
    sessionStorage.setItem('startPort', name);
  } else if (!end) {
    sessionStorage.setItem('endPort', name);
  } else {
    sessionStorage.setItem('startPort', name);
    sessionStorage.removeItem('endPort');
  }
  renderRoute();
}

// 사이드바 화면 갱신: 출발지·도착지 입력 칸 + 버튼 활성화
function renderRoute() {
  const start = sessionStorage.getItem('startPort');  // 없으면 null
  const end = sessionStorage.getItem('endPort');

  // 값이 없으면 빈 칸 -> placeholder("출발지 입력")가 대신 보임
  document.getElementById('start-input').value = start || '';
  document.getElementById('end-input').value = end || '';

  // 지도의 항구 이름표 표시도 같이 갱신 (map.js 가 아직 로딩 전이면 건너뜀)
  if (typeof refreshPortLabels === 'function') refreshPortLabels();
}

document.getElementById('find-route-btn').addEventListener('click', () => {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');
  if (!start || !end) return;  // 출발지·도착지가 다 선택되지 않았으면 눌러도 아무 반응 없음
  // 경로 계산은 route.js 가 맡는다 (서버 /api/route 를 부르고 지도에 선을 그림)
  requestRoute(start, end);
});

// 선택한 출발지/도착지를 sessionStorage에서 지우고 화면 갱신
document.getElementById('reset-btn').addEventListener('click', () => {
  sessionStorage.removeItem('startPort');
  sessionStorage.removeItem('endPort');
  renderRoute();
});

// 입력 칸에 이름을 치고 칸을 벗어나면: 항구 이름이 맞으면 선택, 아니면 선택 취소
function inputPort(inputId, key) {
  document.getElementById(inputId).addEventListener('change', (e) => {
    if (document.querySelector('#port-list option[value="' + e.target.value + '"]')) {
      sessionStorage.setItem(key, e.target.value);
    } else {
      sessionStorage.removeItem(key);
    }
    renderRoute();  // 칸·버튼·지도를 저장값에 맞춤 (없는 이름이면 칸이 비워짐)
  });
}
inputPort('start-input', 'startPort');
inputPort('end-input', 'endPort');

// 페이지를 열 때 저장된 값이 있으면 바로 보여줌
renderRoute();
