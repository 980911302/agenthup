package com.ruoyi.system.kb.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;

/**
 * IR JSON 落盘。模式对齐 {@code ContextFileStore}:库里存路径,正文落文件。
 * <pre>
 * {irRoot}/kb/{kbId}/{docId}.json
 * </pre>
 */
@Component
public class IrFileStore
{
    private static final Logger log = LoggerFactory.getLogger(IrFileStore.class);

    private final Path irRoot;

    public IrFileStore(@Value("${ai.kb.ir-path:./agent-java/ai/kb-ir}") String irPath)
    {
        this.irRoot = Paths.get(irPath);
        try
        {
            Files.createDirectories(irRoot);
        }
        catch (IOException e)
        {
            log.error("无法创建 IR 根目录: {}", irRoot, e);
        }
        log.info("IrFileStore 初始化,根目录={}", irRoot);
    }

    /**
     * 写入 IR,返回相对路径字符串(写入 kb_document.ir_path)。
     */
    public String save(Long kbId, Long docId, IrDoc irDoc)
    {
        Path dir = irRoot.resolve("kb").resolve(String.valueOf(kbId));
        try
        {
            Files.createDirectories(dir);
            Path file = dir.resolve(docId + ".json");
            String json = JSON.toJSONString(irDoc, JSONWriter.Feature.PrettyFormat);
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        }
        catch (IOException e)
        {
            throw new ServiceException("写入 IR 失败: " + e.getMessage());
        }
    }

    public IrDoc load(String irPath)
    {
        if (irPath == null || irPath.isEmpty())
        {
            return null;
        }
        try
        {
            String json = Files.readString(Paths.get(irPath), StandardCharsets.UTF_8);
            return JSON.parseObject(json, IrDoc.class);
        }
        catch (IOException e)
        {
            throw new ServiceException("读取 IR 失败: " + e.getMessage());
        }
    }

    public void deleteQuietly(String irPath)
    {
        if (irPath == null || irPath.isEmpty())
        {
            return;
        }
        try
        {
            Files.deleteIfExists(Paths.get(irPath));
        }
        catch (IOException e)
        {
            log.warn("删除 IR 文件失败: {} - {}", irPath, e.getMessage());
        }
    }
}
