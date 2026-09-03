package com.ruoyi.system.tool;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 同一文件的写操作串行化,不同文件仍并行。
 *
 * <p>对齐 pi 的 {@code file-mutation-queue.ts}。{@link com.ruoyi.system.ai.run.ParallelToolCallingManager}
 * 用 {@code CompletableFuture.runAsync} 真并行执行同一批次的工具调用,模型在一个批次里
 * 发两个 {@code edit} 改同一文件时,两边都读到原文、各自替换、后写的整体覆盖先写的 ——
 * 先那次的修改静默消失,没有任何报错。
 *
 * <p>锁的 key 用 {@code toRealPath()}:软链、相对/绝对路径可能指向同一个文件,
 * 按字符串路径加锁会漏。文件还不存在时(write 新建)退回 {@code normalize()} 后的绝对路径。
 *
 * <p>锁表按引用计数回收 —— 长会话里模型会碰成百上千个文件,只增不删会让 Map 无界增长。
 */
public final class FileMutationQueue
{
    private static final Map<String, Entry> LOCKS = new ConcurrentHashMap<>();

    private FileMutationQueue()
    {
    }

    private static final class Entry
    {
        final ReentrantLock lock = new ReentrantLock();
        /** 持有或等待该锁的线程数,归零时从表里摘除 */
        int refs;
    }

    /**
     * 在文件级互斥下执行写操作。
     *
     * @param file 目标文件,不必已存在
     * @param action 写操作;其返回值原样透传
     */
    public static <T> T withLock(Path file, Supplier<T> action)
    {
        String key = keyOf(file);
        Entry entry = LOCKS.compute(key, (k, cur) -> {
            Entry e = cur != null ? cur : new Entry();
            e.refs++;
            return e;
        });
        entry.lock.lock();
        try
        {
            return action.get();
        }
        finally
        {
            entry.lock.unlock();
            LOCKS.compute(key, (k, cur) -> {
                if (cur == null)
                {
                    return null;
                }
                cur.refs--;
                return cur.refs <= 0 ? null : cur;
            });
        }
    }

    /**
     * 锁 key:优先真实路径,解析失败(文件不存在等)退回归一化绝对路径。
     *
     * <p>两条路径必须对同一个文件给出同一个 key,否则锁不住。
     */
    static String keyOf(Path file)
    {
        try
        {
            return file.toRealPath().toString();
        }
        catch (Exception ignored)
        {
            // 文件还不存在(write 新建)。此时不能直接 normalize —— 路径里若有软链,
            // 等文件建出来后 realPath 会给出另一个 key,两次调用就锁不到一起。
            // 退一步用「父目录的真实路径 + 文件名」,父目录通常已存在。
            Path abs = file.toAbsolutePath().normalize();
            Path parent = abs.getParent();
            if (parent != null)
            {
                try
                {
                    return parent.toRealPath().resolve(abs.getFileName()).toString();
                }
                catch (Exception ignored2)
                {
                    // 父目录也不存在,只能退到纯路径
                }
            }
            return abs.toString();
        }
    }

    /** 仅供测试:当前驻留的锁条目数,用于验证回收 */
    static int activeLockCount()
    {
        return LOCKS.size();
    }
}
