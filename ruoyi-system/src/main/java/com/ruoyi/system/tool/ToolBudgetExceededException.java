package com.ruoyi.system.tool;

/**
 * 工具调用预算硬上限专用异常。
 *
 * <p>软上限只返回提示文本让模型收尾;硬上限才抛本异常兜底,防止模型无视提示继续刷工具。
 * 由 {@code ChatRunExecutor} 既有的 {@code catch (Throwable)} 转为 FAILED 终态。
 *
 * <p><b>为什么直接继承 RuntimeException 而不是 ServiceException</b>:后者是若依框架的
 * 核心类且被声明为 final,继承它就得改 ruoyi-common —— 那会让「只构建 ruoyi-system」
 * 的场景抛出 IncompatibleClassChangeError(本地仓库里的 jar 仍是旧版),
 * 报错信息还完全指不到真正原因。而本异常的唯一消费者是 ChatRunExecutor 的
 * catch (Throwable),继承 RuntimeException 已经够用,不必动框架类。
 *
 * @author ruoyi
 */
public class ToolBudgetExceededException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public ToolBudgetExceededException(String message)
    {
        super(message);
    }
}
