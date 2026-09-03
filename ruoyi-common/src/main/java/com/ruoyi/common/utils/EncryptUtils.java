package com.ruoyi.common.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES/CBC/PKCS5Padding 加解密工具
 * 
 * key 来自 application.yml 的 ruoyi.encrypt.key,必须是 16/24/32 字节的 Base64 字符串
 * iv 固定从 key 派生(前 16 字节),生产可改用随机 iv 与密文一起存储
 * 
 * @author ruoyi
 */
@Component
public class EncryptUtils
{
    private static final Logger log = LoggerFactory.getLogger(EncryptUtils.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    /** 配置中的密钥(Base64 编码),长度决定 AES-128/192/256 */
    @Value("${ruoyi.encrypt.key:}")
    private String key;

    /** 脱敏:apiKey = sk-xxx...xxx 形式,保留前3后4 */
    private static final int KEEP_PREFIX = 3;
    private static final int KEEP_SUFFIX = 4;

    /**
     * 加密(输入明文,返回 Base64)
     */
    public String encrypt(String plain)
    {
        if (plain == null || plain.isEmpty())
        {
            return plain;
        }
        try
        {
            byte[] raw = getKeyBytes();
            SecretKeySpec keySpec = new SecretKeySpec(raw, ALGORITHM);
            IvParameterSpec ivSpec = new IvParameterSpec(raw, 0, 16);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        }
        catch (Exception e)
        {
            log.error("AES加密失败: {}", e.getMessage());
            throw new IllegalStateException("AES加密失败", e);
        }
    }

    /**
     * 解密(输入 Base64,返回明文)
     */
    public String decrypt(String cipherText)
    {
        if (cipherText == null || cipherText.isEmpty())
        {
            return cipherText;
        }
        try
        {
            byte[] raw = getKeyBytes();
            SecretKeySpec keySpec = new SecretKeySpec(raw, ALGORITHM);
            IvParameterSpec ivSpec = new IvParameterSpec(raw, 0, 16);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            log.error("AES解密失败(可能为明文旧数据): {}", e.getMessage());
            return cipherText;
        }
    }

    /**
     * 脱敏显示:sk-abc...xyz
     */
    public String mask(String plain)
    {
        if (plain == null || plain.isEmpty())
        {
            return plain;
        }
        int len = plain.length();
        if (len <= KEEP_PREFIX + KEEP_SUFFIX)
        {
            return "******";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(plain, 0, KEEP_PREFIX);
        sb.append("******");
        sb.append(plain, len - KEEP_SUFFIX, len);
        return sb.toString();
    }

    private byte[] getKeyBytes()
    {
        if (key == null || key.isEmpty())
        {
            throw new IllegalStateException("ruoyi.encrypt.key 未配置,无法加解密");
        }
        return Base64.getDecoder().decode(key);
    }
}
