// 항만 영상: 국립해양조사원 HLS 라이브를 브라우저가 직접 재생 (우리 서버를 거치지 않고, 저장도 안 함)

// 영상 주소 = cctvBase + '/' + 스트림이름 + '/s.m3u8'
const cctvBase = 'https://www.khoa.go.kr/SEAFOG/REDACTED/hls/khoa';

// 항구 이름 -> 스트림 이름
const cctvStreams = {
  '인천항': 'SeaFog_Incheon',
  '평택·당진항': 'SeaFog_PTDJ',
  '대산항': 'SeaFog_Daesan',
  '군산항': 'SeaFog_Gunsan',
  '목포항': 'SeaFog_Mokpo',
  '여수항': 'SeaFog_Yeosu',
  '광양항': 'SeaFog_Yeosu',
  '울산항': 'SeaFog_Ulsan',
  '부산항': 'SeaFog_Busan',
  '포항항': 'SeaFog_Pohang',
  '묵호항': 'Mukho',
};

const cctvModal = document.getElementById('cctv-modal');
const cctvBody = document.getElementById('cctv-body');

// 영상 칸 하나: "출발지: 부산항" + 영상 (없으면 안내 문구)
function cctvMakeItem(role, name) {
  const stream = cctvStreams[name];
  const item = document.createElement('div');
  item.className = 'cctv-item';
  item.innerHTML = `<div class="cctv-title">${role}: ${name}</div>`;

  if (!stream) {
    item.innerHTML += `<div class="cctv-none">${name}은(는) 국립해양조사원이 제공하는 영상이 없습니다</div>`;
    return item;
  }

  item.innerHTML += '<video muted autoplay controls></video>';
  item.querySelector('video').src = cctvBase + '/' + stream + '/s.m3u8';
  return item;
}

// 열기: 선택한 출발지·도착지만 영상 칸을 만듦
function cctvOpen() {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');

  if (start) cctvBody.appendChild(cctvMakeItem('출발지', start));
  if (end) cctvBody.appendChild(cctvMakeItem('도착지', end));
  cctvModal.classList.add('cctv-show');
}

// 닫기: 영상 칸을 지우면 영상 받기도 멈춤
function cctvClose() {
  cctvBody.innerHTML = '';
  cctvModal.classList.remove('cctv-show');
}

// ✕ 버튼 / 어두운 배경 클릭 / ESC 로 닫기
document.getElementById('cctv-close').addEventListener('click', cctvClose);
cctvModal.addEventListener('click', e => {
  if (e.target === cctvModal) cctvClose();  // 흰 상자 안쪽 클릭은 무시
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') cctvClose();
});

// [항만 영상] 버튼은 기상 패널(weather-panel.js)의 HTML 에 있음
document.addEventListener('click', e => {
  if (e.target.closest('.cctv-open-btn')) cctvOpen();
});
