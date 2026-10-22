package com.investory.marketdata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:hithink-data.properties")
@EnableConfigurationProperties(HithinkDataProperties.class)
public class HithinkDataConfiguration { }
