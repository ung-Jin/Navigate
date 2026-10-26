// 볼 시각 (교통현황·안전진단 공통)
// - 지금: 교통 = 실시간 선박(MTIS #244), 안전진단 = 지금 시각이 든 3시간 위험 예보
// - 오늘·내일·모레 + 시각: 교통 = 혼잡 예보(#259, 1시간 단위), 안전진단 = 그 시각이 든 3시간 위험 예보(#261)
// 바뀌면 document 에 'when-changed' 이벤트 → traffic.js·safety.js 가 다시 받음
// traffic.js·safety.js 보다 먼저 불러와야 함

const pad2 = n => String(n).padStart(2, '0');

// 오늘 + n일 → yyyyMMdd (이 컴퓨터 시각 기준)
function ymd(plusDays) {
  const d = new Date();
  d.setDate(d.getDate() + plusDays);
  return d.getFullYear() + pad2(d.getMonth() + 1) + pad2(d.getDate());
}

const whenIsNow = () => document.querySelector('input[name="when-day"]:checked').value === 'now';

// { now, date: yyyyMMdd, hour: 'HH' }
function currentWhen() {
  const day = document.querySelector('input[name="when-day"]:checked').value;
  if (day === 'now') return { now: true, date: ymd(0), hour: pad2(new Date().getHours()) };
  return { now: false, date: ymd(Number(day)), hour: document.getElementById('when-hour').value };
}

// 화면 글자: '지금' 또는 'MM-DD HH시'
function whenLabel(w = currentWhen()) {
  return w.now ? '지금' : `${w.date.slice(4, 6)}-${w.date.slice(6)} ${w.hour}시`;
}

// 시각 고르기 00~23시, 처음엔 다음 정시
(function initWhen() {
  const sel = document.getElementById('when-hour');
  sel.innerHTML = Array.from({ length: 24 }, (_, h) => `<option value="${pad2(h)}">${pad2(h)}시</option>`).join('');
  sel.value = pad2((new Date().getHours() + 1) % 24);

  const changed = () => {
    sel.disabled = whenIsNow();
    document.dispatchEvent(new CustomEvent('when-changed'));
  };
  document.querySelectorAll('input[name="when-day"]').forEach(r => r.addEventListener('change', changed));
  sel.addEventListener('change', changed);
})();
