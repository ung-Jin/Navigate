package com.green.Navigate.main.service;

import com.green.Navigate.main.dto.PortDTO;
import com.green.Navigate.main.mapper.MainMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MainService {

  private final MainMapper mainMapper;

  // 무역항 전체 목록
  public List<PortDTO> getPortList() {
    return mainMapper.selectPortList();
  }
}
