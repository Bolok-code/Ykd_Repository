package com.clitoolbox.ilink.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ILinkServiceImplTest {

    @Test
    void extractsCityAfterCommonWeatherQueryPrefixes() {
        assertEquals("杭州", ILinkServiceImpl.extractCity("查询杭州天气"));
        assertEquals("杭州", ILinkServiceImpl.extractCity("帮我查一下杭州天气"));
        assertEquals("杭州", ILinkServiceImpl.extractCity("请问一下杭州气温"));
        assertEquals("杭州", ILinkServiceImpl.extractCity("我想查询一下杭州多少度"));
    }

    @Test
    void removesParticlesAndTimeWordsAroundCity() {
        assertEquals("杭州", ILinkServiceImpl.extractCity("杭州的天气怎么样"));
        assertEquals("杭州", ILinkServiceImpl.extractCity("杭州今天的天气"));
        assertEquals("杭州", ILinkServiceImpl.extractCity("查询今天杭州天气"));
        assertEquals("杭州", ILinkServiceImpl.extractCity("帮我看看杭州现在的温度"));
    }

    @Test
    void returnsNullWhenMessageHasNoCityOrWeatherIntent() {
        assertNull(ILinkServiceImpl.extractCity("天气怎么样"));
        assertNull(ILinkServiceImpl.extractCity("你好"));
        assertNull(ILinkServiceImpl.extractCity(null));
    }
}
