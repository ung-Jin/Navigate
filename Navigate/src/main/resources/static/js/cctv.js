// 실시간 항만 영상 (국립해양조사원 HLS 라이브)
// 영상은 서버를 거치지 않고 브라우저가 원본 서버에서 바로 재생함 (저장·중계 없음)
// 필요한 것:
//  - hls.js : 영상(.m3u8)을 받아서 재생하는 라이브러리. 버튼을 눌러 재생할 때 쓰임

// 영상 주소 = cctvBase + '/' + 스트림이름 + '/s.m3u8'
// 주소 중간의 긴 문자열이 바뀌면 이 상수 한 곳만 고치면 됨
const cctvBase = 'https://www.khoa.go.kr/SEAFOG/REDACTED/hls/khoa';

// 항구 이름(DB ports.name 과 같아야 함) -> 영상(스트림) 이름
// 이 표에 없는 항구는 "영상이 없습니다" 문구가 나옴
const cctvStreams = {
  '인천항': 'SeaFog_Incheon',
  '평택·당진항': 'SeaFog_PTDJ',
  '대산항': 'SeaFog_Daesan',
  '군산항': 'SeaFog_Gunsan',
  '목포항': 'SeaFog_Mokpo',
  '여수항': 'SeaFog_Yeosu',
  '광양항': 'SeaFog_Yeosu',
  '울산항': 'SeaFog_Ulsan',
  '부산항': 'SeaFog_Busan',   // 부산항(북항) 카메라
  '포항항': 'SeaFog_Pohang',  // 포항 영일만항 카메라
  '묵호항': 'Mukho',          // 해무 CCTV가 아니라 묵호 조위관측소 카메라
};

const cctvModal = document.getElementById('cctv-modal');
const cctvBody = document.getElementById('cctv-body');
let cctvPlayers = [];  // 재생 중인 hls.js 목록 — 닫을 때 모두 정리하려고 보관

// 영상 한 개를 hls.js 로 재생
// video = 재생할 <video>, url = 영상 주소, showError = 오류 글을 화면에 보여주는 함수
function cctvPlay(video, url, showError) {
  const hls = new Hls();
  cctvPlayers.push(hls);  // 닫을 때 정리할 수 있게 목록에 보관

  hls.on(Hls.Events.ERROR, (event, data) => {
    // 재생을 이어갈 수 없는 심각한 오류만 화면에 표시 (가벼운 오류는 hls.js 가 알아서 복구함)
    if (data.fatal) showError('영상을 재생할 수 없습니다. 잠시 후 다시 시도해 주세요.');
  });
  hls.loadSource(url);     // 영상 주소(.m3u8)를 알려주고
  hls.attachMedia(video);  // 이 <video> 에 연결 (<video autoplay> 라서 준비되면 바로 재생됨)
}

// 모달 안의 영상 칸 하나 만들기: "출발지: 부산항" + 영상 (영상이 없으면 안내 문구)
function cctvMakeItem(role, name) {
  const stream = cctvStreams[name];

  const item = document.createElement('div');
  item.className = 'cctv-item';
  item.innerHTML = `
    <div class="cctv-title">${role}: ${name}</div>
    ${stream
      ? '<video muted autoplay controls></video><div class="cctv-error"></div>'
      : `<div class="cctv-none">${name}은(는) 국립해양조사원이 제공하는 영상이 없습니다</div>`}`;

  if (stream) {
    const video = item.querySelector('video');
    const error = item.querySelector('.cctv-error');
    cctvPlay(video, cctvBase + '/' + stream + '/s.m3u8', text => { error.textContent = text; });
  }
  return item;
}

// 모달 열기: 선택한 출발지/도착지의 영상 (한 곳만 골랐으면 그 곳만)
function cctvOpen() {
  const start = sessionStorage.getItem('startPort');
  const end = sessionStorage.getItem('endPort');

  // 선택한 쪽만 영상 칸을 만듦 (선택 안 했으면 null 이라 건너뜀)
  if (start) {
    cctvBody.appendChild(cctvMakeItem('출발지', start));
  }
  if (end) {
    cctvBody.appendChild(cctvMakeItem('도착지', end));
  }
  cctvModal.classList.add('cctv-show');
}

// 모달 닫기: 재생 중인 영상 정리 + 영상 칸 비우기 + 모달 숨기기
function cctvClose() {
  // hls.js 연결을 끊으면 영상 받기(네트워크 요청)도 멈춤
  for (const hls of cctvPlayers) {
    hls.destroy();
  }
  cctvPlayers = [];
  cctvBody.innerHTML = '';
  cctvModal.classList.remove('cctv-show');
}

// 닫기: ✕ 버튼, 바깥(어두운 배경) 클릭, ESC 키
document.getElementById('cctv-close').addEventListener('click', cctvClose);
cctvModal.addEventListener('click', e => {
  if (e.target === cctvModal) cctvClose();  // 흰 상자 안쪽 클릭은 무시
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') cctvClose();
});

// 기상 패널의 날씨 카드 아래에 [항만 영상] 버튼 넣기. 넣었으면 true, 패널이 아직 없으면 false
// 기상 패널은 weather-panel.js 가 만들어서 언제 생기는지 알 수 없음 -> 아래에서 생길 때까지 0.3초마다 다시 시도
function cctvAddButton() {
  const cards = document.querySelector('#wx-panel .wx-cards');
  if (!cards) return false;

  const btn = document.createElement('button');
  btn.className = 'cctv-open-btn';
  btn.textContent = '📹 항만 영상';
  btn.addEventListener('click', cctvOpen);
  cards.insertAdjacentElement('afterend', btn);
  return true;
}
// 버튼을 달면 반복을 멈춤
const cctvTimer = setInterval(() => {
  if (cctvAddButton()) clearInterval(cctvTimer);
}, 300);
