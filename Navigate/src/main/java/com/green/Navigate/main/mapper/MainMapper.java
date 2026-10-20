package com.green.Navigate.main.mapper;

import com.green.Navigate.main.dto.PortDTO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;


@Mapper
public interface MainMapper {

  // 무역항 31개 전체 조회
  List<PortDTO> selectPortList();
}
