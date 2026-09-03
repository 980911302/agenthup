package com.ruoyi.web.websocket.chat;

/** WebSocket 一次性票据中保存的最小身份快照。 */
public class ChatWebSocketIdentity
{
    private Long userId;
    private String username;
    private boolean admin;
    private long expiresAt;

    public ChatWebSocketIdentity() { }

    public ChatWebSocketIdentity(Long userId, String username, boolean admin, long expiresAt)
    {
        this.userId = userId;
        this.username = username;
        this.admin = admin;
        this.expiresAt = expiresAt;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
}

