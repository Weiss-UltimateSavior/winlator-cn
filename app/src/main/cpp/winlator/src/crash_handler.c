#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include "crash_handler.h"

#define CRASH_LOG_TAG "CrashHandler"
#define CRASH_MAX_FRAMES 64
#define CRASH_LINEBUF_SIZE 512
#define CRASH_MAPS_MAX_BYTES (256 * 1024)
#define CRASH_ALT_STACK_SIZE (64 * 1024)
// bionic 的 NSIG 并非在所有 API 级别的头文件里都可见，这里按 Linux 上限自定
#define CRASH_NSIG 65

// 崩溃时进程状态已不可信：malloc/stdio 都可能死锁在已持有的锁上。因此日志句柄自给自足，
// 全程只用 open/write/close/fsync 等 async-signal-safe 调用，不碰任何需要加锁的东西。
static int crashLogFd = -1;

// Android 的 debuggerd 本身就是通过 signal handler 实现的，我们替换它后必须在写完日志
// 把信号交回去，否则 tombstone 会丢失——那损失比崩溃日志本身还大。
static struct sigaction g_oldActions[CRASH_NSIG];

static const int CRASH_SIGNALS[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
#define CRASH_NUM_SIGNALS ((int)(sizeof(CRASH_SIGNALS) / sizeof(CRASH_SIGNALS[0])))

// 栈溢出时当前栈已不可用于执行 handler，需要备用栈。sigaltstack 是 per-thread 的，
// 这里只能保护调用 init 的那个线程（主线程），聊胜于无。
static char g_altStack[CRASH_ALT_STACK_SIZE];

static void crashWrite(const char* text) {
    if (crashLogFd >= 0) write(crashLogFd, text, strlen(text));
}

// 不能用 snprintf（stdio 非 async-signal-safe）。这里只支持 %s / %d / %p，
// 足够崩溃日志使用，且不依赖任何共享状态。
static void crashWriteFormat(const char* fmt, ...) {
    if (crashLogFd < 0) return;

    char buffer[CRASH_LINEBUF_SIZE];
    int pos = 0;
    va_list args;
    va_start(args, fmt);

    for (const char* p = fmt; *p && pos < CRASH_LINEBUF_SIZE - 1; p++) {
        if (*p != '%') {
            buffer[pos++] = *p;
            continue;
        }

        char tmp[32];
        int tmpLen = 0;
        const char* str = NULL;

        switch (*(p + 1)) {
            case 's': {
                str = va_arg(args, const char*);
                break;
            }
            case 'd': {
                long value = va_arg(args, int);
                int negative = value < 0;
                unsigned long magnitude = negative ? (unsigned long)(-value) : (unsigned long)value;
                do {
                    tmp[tmpLen++] = (char)('0' + (magnitude % 10));
                    magnitude /= 10;
                }
                while (magnitude && tmpLen < (int)sizeof(tmp) - 2);
                if (negative) tmp[tmpLen++] = '-';
                break;
            }
            case 'p': {
                unsigned long value = (unsigned long)va_arg(args, void*);
                do {
                    tmp[tmpLen++] = "0123456789abcdef"[value & 0xful];
                    value >>= 4;
                }
                while (value && tmpLen < (int)sizeof(tmp) - 2);
                tmp[tmpLen++] = 'x';
                tmp[tmpLen++] = '0';
                break;
            }
            default:
                buffer[pos++] = *p;
                continue;
        }
        p++;

        if (*p == 'd' || *p == 'p') {
            // %d 与 %p 是反序填入 tmp 的，这里翻转回来
            for (int i = 0; i < tmpLen / 2; i++) {
                char c = tmp[i];
                tmp[i] = tmp[tmpLen - 1 - i];
                tmp[tmpLen - 1 - i] = c;
            }
            tmp[tmpLen] = '\0';
            str = tmp;
        }
        if (!str) str = "(null)";

        for (const char* s = str; *s && pos < CRASH_LINEBUF_SIZE - 1; s++) buffer[pos++] = *s;
    }

    buffer[pos] = '\0';
    va_end(args);
    write(crashLogFd, buffer, (size_t)pos);
}

static const char* signalName(int signo) {
    switch (signo) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        default: return "UNKNOWN";
    }
}

static const char* siCodeDescription(int signo, int code) {
    if (signo == SIGSEGV || signo == SIGBUS) {
        switch (code) {
            case SEGV_MAPERR: return "SEGV_MAPERR (address not mapped)";
            case SEGV_ACCERR: return "SEGV_ACCERR (invalid permissions)";
            default: return NULL;
        }
    }
    return NULL;
}

typedef struct {
    uintptr_t* frames;
    int maxFrames;
    int numFrames;
} UnwindState;

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context* context, void* arg) {
    UnwindState* state = (UnwindState*)arg;
    if (state->numFrames >= state->maxFrames) return _URC_END_OF_STACK;

    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) state->frames[state->numFrames++] = pc;
    return _URC_NO_REASON;
}

// 把 /proc/self/maps 写进日志。没有符号表时，靠它把 backtrace 里的绝对地址换算成
// "哪个 so + 多少偏移"，是崩溃日志里最有价值的部分。设上限避免填满存储。
static void dumpMaps() {
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return;

    char buffer[4096];
    ssize_t bytesRead;
    int total = 0;
    while (total < CRASH_MAPS_MAX_BYTES && (bytesRead = read(fd, buffer, sizeof(buffer))) > 0) {
        if (write(crashLogFd, buffer, (size_t)bytesRead) < 0) break;
        total += (int)bytesRead;
    }
    close(fd);
}

static void crashSignalHandler(int signo, siginfo_t* info, void* context) {
    // handler 自身再次触发信号时不要递归，直接交回原处理器
    static volatile sig_atomic_t inHandler = 0;
    if (inHandler) goto chain;
    inHandler = 1;

    // 每个进程只记录第一次崩溃。
    // ART 在虚拟机启动时就装了 SIGSEGV handler，用于"隐式空指针检查"（故意解引用空地址
    // 触发 SIGSEGV 再转成 Java 的 NullPointerException）与栈溢出检测；它装得比我们早，
    // 因此我们的 handler 排在前面，会先接到这些本不是崩溃的信号。若不限流，循环里抛
    // NPE 就会被反复记为崩溃，每次还附带一次 maps dump + fsync（几十毫秒），从诊断工具
    // 变成负优化，并把真正的崩溃记录冲掉。
    // 只记首次：真正的崩溃（最有诊断价值的那次）完整保留，误报代价硬性封顶为一次。
    // 注意不能用 si_addr < 4096 过滤来绕开——那是 ART 隐式空检查的特征，但真实崩溃
    // （如 gl_framebuffer.c 注释里记录的 fault addr 0x80）同样落在这个范围，会误杀。
    static volatile sig_atomic_t alreadyLogged = 0;
    if (alreadyLogged) goto chain;
    alreadyLogged = 1;

    ucontext_t* uc = (ucontext_t*)context;

    crashWrite("\n========== native crash ==========\n");
    crashWriteFormat("signal: %s (%d)\n", signalName(signo), signo);
    crashWriteFormat("pid: %d tid: %d\n", (int)getpid(), (int)gettid());

    if (info) {
        const char* codeDesc = siCodeDescription(signo, info->si_code);
        if (codeDesc) crashWriteFormat("code: %s\n", codeDesc);
        // fault addr 只对 SEGV/BUS 有意义，其它信号打印会误导
        if (signo == SIGSEGV || signo == SIGBUS) {
            crashWriteFormat("fault addr: %p\n", info->si_addr);
        }
    }

    // ARM64 下 PC/LR/SP 在 ucontext 的固定偏移处
    crashWriteFormat("pc: %p\n", (void*)uc->uc_mcontext.pc);
    crashWriteFormat("lr: %p\n", (void*)uc->uc_mcontext.regs[30]);
    crashWriteFormat("sp: %p\n", (void*)uc->uc_mcontext.sp);

    uintptr_t frames[CRASH_MAX_FRAMES];
    UnwindState state = {frames, CRASH_MAX_FRAMES, 0};
    _Unwind_Backtrace(unwindCallback, &state);

    crashWrite("\nbacktrace:\n");
    if (state.numFrames == 0) crashWrite("  (unwind failed or empty)\n");
    for (int i = 0; i < state.numFrames; i++) {
        crashWriteFormat("  #%d %p\n", i, (void*)frames[i]);
    }

    crashWrite("\nmaps:\n");
    dumpMaps();
    crashWrite("\n========== end of native crash ==========\n\n");

    fsync(crashLogFd);
    __android_log_print(ANDROID_LOG_ERROR, CRASH_LOG_TAG, "native crash (%s) captured", signalName(signo));

chain:
    // 交回 debuggerd（或安装前的处理器），保证 tombstone 照常生成。
    // 用花括号包住：goto 不能跨过变量声明，否则依赖 C23 扩展。
    {
        struct sigaction* old = &g_oldActions[signo];
        if (old->sa_handler == SIG_DFL || old->sa_handler == SIG_IGN) {
            signal(signo, SIG_DFL);
            raise(signo);
        }
        else if (old->sa_flags & SA_SIGINFO) {
            old->sa_sigaction(signo, info, context);
        }
        else {
            old->sa_handler(signo);
        }
    }
}

void crashHandlerInit(const char* logFilePath) {
    if (logFilePath && crashLogFd < 0) {
        crashLogFd = open(logFilePath, O_WRONLY | O_CREAT | O_APPEND, 0666);
    }

    stack_t altStack;
    memset(&altStack, 0, sizeof(altStack));
    altStack.ss_sp = g_altStack;
    altStack.ss_size = sizeof(g_altStack);
    altStack.ss_flags = 0;
    sigaltstack(&altStack, NULL);

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = crashSignalHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&action.sa_mask);
    // 处理期间屏蔽其它崩溃信号，避免嵌套
    for (int i = 0; i < CRASH_NUM_SIGNALS; i++) sigaddset(&action.sa_mask, CRASH_SIGNALS[i]);

    for (int i = 0; i < CRASH_NUM_SIGNALS; i++) {
        int signo = CRASH_SIGNALS[i];
        // 第二个参数传出的是 debuggerd 的 handler，chain 时交回给它
        sigaction(signo, &action, &g_oldActions[signo]);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_MainApplication_installNativeCrashHandler(JNIEnv *env, jclass obj, jstring logFilePath) {
    (void)obj; // 静态方法，不需要 this
    const char* path = logFilePath ? (*env)->GetStringUTFChars(env, logFilePath, NULL) : NULL;
    crashHandlerInit(path);
    if (path) (*env)->ReleaseStringUTFChars(env, logFilePath, path);
}
