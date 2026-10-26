// 실시간 항만 영상 (국립해양조사원 HLS 라이브) — 기상 패널의 [항만 영상] 버튼을 누르면 모달이 열림
// 영상은 서버를 거치지 않고 브라우저가 원본 서버에서 바로 재생함 (저장·중계 없음)
// hls.js(라이브러리)와 weather-panel.js 다음에 불러와야 함 (이름은 모두 cctv 로 시작해서 다른 코드와 안 겹침)

// 영상 주소 = cctvBase + '/' + 스트림이름 + '/s.m3u8'
// 주소 중간의 긴 문자열이 바뀌면 이 상수 한 곳만 고치면 됨
const cctvBase = 'https://www.khoa.go.kr/SEAFOG/REDACTED/hls/khoa';

// 라이브 영상용 hls.js 옵션 (지연을 줄이고 버퍼는 적게)
const cctvHlsOptions = { startPosition: -1, liveSyncDurationCount: 1, startFragPrefetch: true, maxBufferLength: 8, backBufferLength: 0 };

// 항구 이름(DB ports.name 과 같아야 함) -> 보여줄 영상 { stream: 스트림 이름, note: 안내 글 }
// 이 표에 없는 항구는 "영상이 없습니다" 문구가 나옴

const cctvStreams = {
  '인천항': { stream: 'SeaFog_Incheon' },
  '평택·당진항': { stream: 'SeaFog_PTDJ' },
  '대산항': { stream: 'SeaFog_Daesan' },
  '군산항': { stream: 'SeaFog_Gunsan' },
  '목포항': { stream: 'SeaFog_Mokpo' },
  '여수항': { stream: 'SeaFog_Yeosu' },
  '광양항': { stream: 'SeaFog_Yeosu' },
  '울산항': { stream: 'SeaFog_Ulsan' },

  // DB 이름과 영상 이름이 조금 다른 항구
  '부산항': { stream: 'SeaFog_Busan', note: '부산항(북항) 영상' },
  '포항항': { stream: 'SeaFog_Pohang', note: '포항 영일만항 영상' },
  '묵호항': { stream: 'Mukho', note: '동해항 영상' },

  // 영상이 없는 항구 중 20km 이내에 있는 항구의 영상 (거리는 DB 좌표로 계산한 값)
  '경인항': { stream: 'SeaFog_Incheon', note: '근처 인천항 영상 (약 11km)' },
  '장항항': { stream: 'SeaFog_Gunsan', note: '근처 군산항 영상 (약 10km)' },
  '태안항': { stream: 'SeaFog_Daesan', note: '근처 대산항 영상 (약 18km)' },
  '진해항': { stream: 'SeaFog_NBusan', note: '근처 부산신항 영상 (약 12km)' },
  '하동항': { stream: 'SeaFog_Yeosu', note: '근처 광양항 영상 (약 13km)' },
  '삼척항': { stream: 'Mukho', note: '근처 묵호항 영상 (약 15km)' },
  '옥계항': { stream: 'Mukho', note: '근처 묵호항 영상 (약 9km)' },
};

const cctvModal = document.getElementById('cctv-modal');
const cctvBody = document.getElementById('cctv-body');
let cctvPlayers = [];  // 재생 중인 영상 목록 [{ hls, video }] — 닫을 때 모두 정리하려고 보관

// 영상 한 개 재생 (크롬 전용: hls.js 로만 재생)
function cctvPlay(video, url, showError) {
  const player = { hls: new Hls(cctvHlsOptions), video: video };
  cctvPlayers.push(player);

  player.hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
  player.hls.on(Hls.Events.ERROR, (event, data) => {
    if (!data.fatal) return;  // 가벼운 오류는 hls.js 가 알아서 복구함
    showError('영상을 재생할 수 없습니다. 잠시 후 다시 시도해 주세요.');
    player.hls.destroy();  // 이 영상만 정리 (모달과 다른 영상은 계속)
  });
  player.hls.loadSource(url);
  player.hls.attachMedia(video);
}

// 모달 안의 영상 칸 하나 만들기: "출발지: 부산항" + 영상 (영상이 없으면 안내 문구)
function cctvMakeItem(role, name) {
  const item = document.createElement('div');
  item.className = 'cctv-item';

  const title = document.createElement('div');
  title.className = 'cctv-title';
  title.textContent = role + ': ' + name;
  item.appendChild(title);

  const info = cctvStreams[name];

  // 안내 글 줄은 글이 없어도 항상 만들어서, 두 칸의 영상(또는 안내 문구) 시작 높이가 서로 맞게 함
  const note = document.createElement('div');
  note.className = 'cctv-note';
  note.textContent = info && info.note ? info.note : '';
  item.appendChild(note);

  if (!info) {
    const none = document.createElement('div');
    none.className = 'cctv-none';
    none.textContent = name + '은(는) 국립해양조사원이 제공하는 영상이 없습니다';
    item.appendChild(none);
    return item;
  }

  const video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  video.controls = true;
  item.appendChild(video);

  const error = document.createElement('div');
  error.className = 'cctv-error';
  item.appendChild(error);

  cctvPlay(video, cctvBase + '/' + info.stream + '/s.m3u8', text => { error.textContent = text; });
  return item;
}

// 재생 중인 영상을 모두 정리 (hls 끊기 + 재생 멈춤 + 영상 주소 제거 -> 네트워크 요청 중단)
function cctvStop() {
  cctvPlayers.forEach(p => {
    if (p.hls) p.hls.destroy();
    p.video.pause();
    p.video.removeAttribute('src');
    p.video.load();
  });
  cctvPlayers = [];
}

// 모달 열기: 선택한 출발지/도착지의 영상 (한 곳만 골랐으면 그 곳만)
function cctvOpen() {
  cctvStop();
  cctvBody.innerHTML = '';

  const picked = [
    ['출발지', sessionStorage.getItem('startPort')],
    ['도착지', sessionStorage.getItem('endPort')],
  ].filter(p => p[1]);  // 선택 안 한 쪽(null)은 뺌

  picked.forEach(p => cctvBody.appendChild(cctvMakeItem(p[0], p[1])));
  cctvModal.classList.add('cctv-show');
}

// 모달 닫기
function cctvClose() {
  cctvStop();
  cctvBody.innerHTML = '';
  cctvModal.classList.remove('cctv-show');
}

// 닫기: ✕ 버튼, 바깥(어두운 배경) 클릭, ESC 키
document.getElementById('cctv-close').addEventListener('click', cctvClose);
cctvModal.addEventListener('click', e => {
  if (e.target === cctvModal) cctvClose();  // 흰 상자 안쪽 클릭은 무시
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && cctvModal.classList.contains('cctv-show')) cctvClose();
});

// 기상 패널(weather-panel.js 가 만듦)의 날씨 카드 아래에 [항만 영상] 버튼 넣기
// weather-panel.js 는 고치지 않으려고, 패널이 생길 때까지 0.3초마다 확인
function cctvAddButton() {
  if (document.getElementById('cctv-open-btn')) return true;
  const cards = document.querySelector('#wx-panel .wx-cards');
  if (!cards) return false;

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.id = 'cctv-open-btn';
  btn.className = 'cctv-open-btn';
  btn.textContent = '📹 항만 영상';
  btn.addEventListener('click', cctvOpen);
  cards.insertAdjacentElement('afterend', btn);
  return true;
}
const cctvTimer = setInterval(() => {
  if (cctvAddButton()) clearInterval(cctvTimer);
}, 300);
