#ifndef CRASH_HANDLER_H
#define CRASH_HANDLER_H

/**
 * 安装 native 崩溃信号处理器（SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGTRAP），
 * 在进程被杀之前把信号、寄存器与 backtrace 追加写入日志文件。
 *
 * 覆盖 gladio / vortek / virglrenderer 等主进程内的 native 崩溃——这些崩溃不经过
 * JVM，Java 侧的 UncaughtExceptionHandler 完全感知不到。写完后会把信号交回
 * Android 的 debuggerd，tombstone 照常生成。
 *
 * 每进程只记录第一次崩溃：ART 的隐式空指针检查也会产生 SIGSEGV，且它的 handler
 * 装得比我们早（我们排在前面会先接到），不限流会把普通 Java NPE 反复记成崩溃。
 *
 * 仅对调用它的线程安装备用栈（sigaltstack 是 per-thread 的），且日志文件句柄
 * 在崩溃时以 async-signal-safe 方式写入，不使用 malloc/stdio。
 *
 * @param logFilePath 日志文件路径（建议放外部存储，便于取出），传 NULL 则只保留 tombstone
 */
extern void crashHandlerInit(const char* logFilePath);

#endif
