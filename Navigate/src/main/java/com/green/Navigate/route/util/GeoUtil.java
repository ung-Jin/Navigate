package com.green.Navigate.route.util;


// 위경도 계산 모음
// RouteService(경로 전체 거리)와 AStar(칸과 칸 사이 거리)가 같이 쓴다.
public class GeoUtil {

  // 지구 반지름 (km)
  private static final double EARTH_RADIUS_KM = 6371.0;

  // 도구 모음이라 객체를 만들 일이 없다.
  private GeoUtil() {
  }


  // 두 지점 사이의 거리를 km 로 구한다. (하버사인 공식)
  //
  // 지구는 둥글기 때문에 평면에서처럼 피타고라스로 구하면 먼 거리일수록 오차가 커진다.
  // 하버사인은 구(球) 위의 두 점 사이 최단 거리를 구하는 공식이다.
  public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {

    // 각도(도) 를 라디안으로 바꾼다. 삼각함수는 라디안을 쓰기 때문.
    double radLat1 = Math.toRadians(lat1);
    double radLat2 = Math.toRadians(lat2);
    double diffLat = Math.toRadians(lat2 - lat1);
    double diffLng = Math.toRadians(lng2 - lng1);

    double a = Math.sin(diffLat / 2) * Math.sin(diffLat / 2)
             + Math.cos(radLat1) * Math.cos(radLat2)
             * Math.sin(diffLng / 2) * Math.sin(diffLng / 2);

    double c = 2 * Math.asin(Math.sqrt(a));

    return EARTH_RADIUS_KM * c;
  }


  // {위도, 경도} 두 점 사이의 거리 (위 메서드를 배열로 받는 형태)
  public static double distanceKm(double[] a, double[] b) {
    return distanceKm(a[0], a[1], b[0], b[1]);
  }
}
