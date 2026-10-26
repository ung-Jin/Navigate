package com.green.Navigate.mtis;

// MTIS 호출 실패 (키 없음, 하루 한도 도달, 네트워크 오류, 응답 코드 오류)
public class MtisException extends RuntimeException {

  private final String code;  // MTIS 결과코드 (예: "153" = 해당 조건 자료 없음), 없으면 null

  public MtisException(String message) {
    this(message, null);
  }

  public MtisException(String message, String code) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
