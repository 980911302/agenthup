package com.ruoyi.toolmcpserver;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;

/**
 * /ws REST 的异常 → RuoYi 风格 JSON(code/msg),与主应用工作区接口一致,
 * 避免裸 500 堆栈直接吐给抽屉前端。（/mcp 是裸 Servlet,不走 DispatcherServlet,
 * 由 McpToolBootstrap 自己兜住错误,与本类无关。）
 */
@RestControllerAdvice
public class McpWebExceptionHandler
{
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e)
    {
        return AjaxResult.error(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public AjaxResult handleOther(Exception e)
    {
        String msg = e.getMessage();
        return AjaxResult.error(msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg);
    }
}