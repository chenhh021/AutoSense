package com.chh.autosense.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Flex Mapper 扫描(独立于应用主类,避免 @WebMvcTest 装配 Mapper)。
 */
@Configuration
@MapperScan("com.chh.autosense.mapper")
public class MyBatisFlexConfig {
}
