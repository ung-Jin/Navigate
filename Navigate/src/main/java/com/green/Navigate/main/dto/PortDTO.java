package com.green.Navigate.main.dto;

import lombok.Data;


@Data
public class PortDTO {
  private String name;  // 항 이름
  private Double lat;   // 위도
  private Double lng;   // 경도
}
