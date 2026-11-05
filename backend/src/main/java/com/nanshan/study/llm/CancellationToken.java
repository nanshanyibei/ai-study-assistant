package com.nanshan.study.llm;

/**
 * 取消令牌。流式场景下客户端随时可能断开或点“停止”，
 * 靠一个 volatile 标志位让读流线程尽快退出，避免继续消耗 token 和连接。
 * <p>
 * 关键点：标志位检查只能发生在「两行数据之间」。如果读流线程正阻塞在
 * readLine() 上（上游 hang 住、一行都不来），标志位永远轮不到检查。
 * 所以 cancel() 会同步执行注册的 onCancel 动作——通常是强制 close 上游流，
 * 让阻塞中的 readLine 抛 IOException 立刻退出。
 */
public class CancellationToken {

    private volatile boolean cancelled;
    private volatile String reason;
    private volatile Runnable onCancelAction;

    /** 注册取消时的强制中断动作（如关闭上游 socket），重复注册会覆盖 */
    public void onCancel(Runnable action) {
        this.onCancelAction = action;
    }

    public void cancel(String reason) {
        this.reason = reason;
        this.cancelled = true;
        Runnable action = this.onCancelAction;
        if (action != null) {
            try {
                action.run();
            } catch (Exception ignored) {
                // 中断动作失败不影响取消本身
            }
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String reason() {
        return reason;
    }
}
