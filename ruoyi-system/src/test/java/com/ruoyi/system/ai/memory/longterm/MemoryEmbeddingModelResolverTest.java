package com.ruoyi.system.ai.memory.longterm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.system.service.ISysConfigService;

/**
 * 记忆向量模型解析:默认跟随知识库的平台全局配置,显式配置可覆盖。
 *
 * <p>锁住的核心语义是「记忆不再单独维护一份向量模型配置」—— 平台的向量模型本来就是
 * 全局一份({@code sys_config} 的 {@code kb.default.embeddingModel}),知识库建库时
 * 把它快照固化到自己那一行,记忆没有建库动作,所以直接读全局值。
 */
class MemoryEmbeddingModelResolverTest
{
    private MemoryEmbeddingModelResolver resolver;
    private ISysConfigService configService;

    @BeforeEach
    void setUp() throws Exception
    {
        resolver = new MemoryEmbeddingModelResolver();
        configService = mock(ISysConfigService.class);
        MemoryTestSupport.setField(resolver, "configService", configService);
        MemoryTestSupport.setField(resolver, "override", "");
    }

    @Test
    void resolve_fallsBackToKbGlobalConfig()
    {
        when(configService.selectConfigByKey(
                MemoryEmbeddingModelResolver.KB_GLOBAL_EMBEDDING_KEY)).thenReturn("bge-m3-zh");

        assertEquals("bge-m3-zh", resolver.resolve(), "留空时必须跟随知识库全局配置");
    }

    @Test
    void resolve_explicitOverrideWins_andSkipsConfigLookup() throws Exception
    {
        MemoryTestSupport.setField(resolver, "override", "  cheap-embed  ");

        assertEquals("cheap-embed", resolver.resolve(), "显式配置优先,且要 trim");
        verify(configService, times(0)).selectConfigByKey(anyString());
    }

    @Test
    void resolve_bothMissing_returnsNull_forCallerToDegrade()
    {
        when(configService.selectConfigByKey(anyString())).thenReturn("");

        assertNull(resolver.resolve(), "都没配时返回 null,由调用方降级而不是抛");
    }

    @Test
    void resolve_configThrows_returnsNull_notPropagated()
    {
        when(configService.selectConfigByKey(anyString()))
                .thenThrow(new RuntimeException("数据源炸了"));

        assertNull(resolver.resolve(), "配置读失败不能拖累对话,按未配置降级");
    }

    @Test
    void resolve_cachesWithinTtl_thenRereadsAfterInvalidate()
    {
        when(configService.selectConfigByKey(anyString())).thenReturn("m1");

        assertEquals("m1", resolver.resolve());
        assertEquals("m1", resolver.resolve());
        assertEquals("m1", resolver.resolve());
        verify(configService, times(1)).selectConfigByKey(anyString());

        // 解析在每轮对话的读侧热路径上,不能每轮打一次 sys_config
        when(configService.selectConfigByKey(anyString())).thenReturn("m2");
        assertEquals("m1", resolver.resolve(), "TTL 内不重读");

        resolver.invalidate();
        assertEquals("m2", resolver.resolve(), "失效后重读");
    }

    @Test
    void resolve_noConfigService_returnsNull()
    {
        MemoryEmbeddingModelResolver bare = new MemoryEmbeddingModelResolver();
        assertNull(bare.resolve(), "未装配 configService 时不炸");
    }
}
