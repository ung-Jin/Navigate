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

// 사이드바 화면 갱신: "출발지 -> 도착지" 글자 + 버튼 활성화
function renderRoute() {
  const start = sessionStorage.getItem('startPort');  // 없으면 null
  const end = sessionStorage.getItem('endPort');

  document.getElementById('route-text').textContent = (start || '출발지 선택') + ' -> ' + (end || '도착지 선택');

  // 둘 다 선택됐을 때만 버튼을 누를 수 있음
  document.getElementById('find-route-btn').disabled = !(start && end);

  // 지도의 항구 이름표 표시도 같이 갱신 (map.js 가 아직 로딩 전이면 건너뜀)
  if (typeof refreshPortLabels === 'function') refreshPortLabels();
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
