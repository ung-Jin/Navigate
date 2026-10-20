// 선택한 항구의 이름만 sessionStorage에 저장 
// 키 이름: startPort(출발지), endPort(도착지)   값 예시: '부산항'
// 좌표가 필요하면 이 이름으로 DB(ports 테이블)에서 찾으면 됨

// 항구 클릭 (지도의 마커나 이름표를 누르면 map.js가 호출함)
// 첫 번째 클릭 = 출발지, 두 번째 클릭 = 도착지, 이미 둘 다 있으면 출발지부터 다시 선택
function clickPort(name) {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');

  if (!start || end) {
    // 출발지가 없거나, 둘 다 이미 선택된 상태 -> 새로 출발지부터
    sessionStorage.removeItem('endPort');
    sessionStorage.setItem('startPort', name);
  } else if (name !== start) {
    // 출발지만 있는 상태 -> 도착지 (출발지와 같은 항구는 무시)
    sessionStorage.setItem('endPort', name);
  }
  renderRoute();
}

// 사이드바 화면 갱신: "출발지 -> 도착지" 글자 + 버튼 활성화
function renderRoute() {
  const start = sessionStorage.getItem('startPort');  // 없으면 null
  const end = sessionStorage.getItem('endPort');

  const startName = start || '출발지 선택';
  const endName = end || '도착지 선택';
  document.getElementById('route-text').textContent = startName + ' -> ' + endName;

  // 둘 다 선택됐을 때만 버튼을 누를 수 있음
  document.getElementById('find-route-btn').disabled = !(start && end);
}

document.getElementById('find-route-btn').addEventListener('click', () => {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');
  // 자리만 만들어 둔 상태: 경로 계산 담당자가 여기에 호출 코드를 넣으면 됨
  console.log('경로찾기', start, end);
});

// 선택한 출발지/도착지를 sessionStorage에서 지우고 화면 갱신
document.getElementById('reset-btn').addEventListener('click', () => {
  sessionStorage.removeItem('startPort');
  sessionStorage.removeItem('endPort');
  renderRoute();
});

// 페이지를 열 때 저장된 값이 있으면 바로 보여줌
renderRoute();
