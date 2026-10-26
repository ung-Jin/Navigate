package com.green.Navigate.mtis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// MTIS 해양격자 ID ↔ 위경도 변환, 국내 해역 판정
public class MtisGrid {

  private static final String L = "ABCDEFGHIJKLMNOPQRSTUVWXY";
  private static final Pattern GRID = Pattern.compile("^(?:GR4_)?([A-Z])(\\d)([A-Z])(\\d)(\\d)_([A-Z])(\\d)$");

  // 국내 해역 근사 경계 (위도, 경도). 서해 124°E · NLL · 한·일 대륙붕 경계선을 단순화한 것
  // 법적 경계가 아니라 "국내 해역만 보기" 용도. 대마도·규슈·중국 연안 격자를 걸러냄
  private static final double[][] KOREA_WATERS = {
      {38.10, 124.30}, {38.00, 124.95}, {37.70, 125.70}, {37.75, 126.20}, {37.80, 126.70},
      {38.62, 128.36}, {38.62, 132.00}, {36.50, 132.00}, {36.18, 131.25}, {35.79, 130.70},
      {35.41, 130.39}, {35.16, 130.01}, {34.76, 129.21}, {34.62, 129.08}, {34.37, 128.79},
      {34.13, 128.50}, {33.84, 128.11}, {33.60, 127.95}, {33.27, 127.81}, {32.96, 127.68},
      {32.60, 126.50}, {32.80, 125.00}, {33.30, 124.00}, {38.10, 124.00},
  };

  private MtisGrid() {
  }

  // 격자 ID (예: GR4_F2O41_P1) → 격자 가운데 {위도, 경도}. 형식이 다르면 null
  // 한 칸 = 0.025° × 0.025° (약 2.8 × 2.3km)
  public static double[] toLatLng(String gridId) {
    Matcher m = GRID.matcher(gridId == null ? "" : gridId);
    if (!m.matches()) {
      return null;
    }
    double lat = 32.0;
    double lng = 80 + 8 * L.indexOf(m.group(1));

    double[] q = quad(m.group(2), 4);
    lat += q[0];
    lng += q[1];

    int i = L.indexOf(m.group(3));
    lat += 3 - i / 4;
    lng += i % 4;

    q = quad(m.group(4), 0.5);
    lat += q[0];
    lng += q[1];

    q = quad(m.group(5), 0.25);
    lat += q[0];
    lng += q[1];

    int k = L.indexOf(m.group(6));
    lat += (4 - k / 5) * 0.05;
    lng += (k % 5) * 0.05;

    q = quad(m.group(7), 0.025);
    lat += q[0];
    lng += q[1];

    return new double[]{round4(lat + 0.0125), round4(lng + 0.0125)};
  }

  // 사분면 번호 (1 북서, 2 북동, 3 남서, 4 남동) → {위도 더할 값, 경도 더할 값}
  private static double[] quad(String digit, double size) {
    int q = Integer.parseInt(digit) - 1;
    return new double[]{size * (1 - q / 2), size * (q % 2)};
  }

  private static double round4(double v) {
    return Math.round(v * 10000) / 10000.0;
  }

  // 점이 국내 해역 다각형 안에 있는가 (ray casting)
  public static boolean inKorea(double lat, double lng) {
    boolean inside = false;
    int j = KOREA_WATERS.length - 1;
    for (int i = 0; i < KOREA_WATERS.length; i++) {
      double yi = KOREA_WATERS[i][0], xi = KOREA_WATERS[i][1];
      double yj = KOREA_WATERS[j][0], xj = KOREA_WATERS[j][1];
      if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
        inside = !inside;
      }
      j = i;
    }
    return inside;
  }
}
