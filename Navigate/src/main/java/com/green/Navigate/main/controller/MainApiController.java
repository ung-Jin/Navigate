package com.green.Navigate.main.controller;

import com.green.Navigate.main.dto.PortDTO;
import com.green.Navigate.main.service.MainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequiredArgsConstructor
public class MainApiController {

  private final MainService mainService;

  // 지도에 찍을 무역항 목록
  @GetMapping("/api/ports")
  public List<PortDTO> ports() {
    return mainService.getPortList();
  }
}
