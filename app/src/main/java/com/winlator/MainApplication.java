package com.winlator;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.winlator.core.AppUtils;
import com.winlator.core.PatchUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainApplication extends Application {
    static {
        // 必须在这里加载：onCreate 会调用 installNativeCrashHandler，而 libwinlator 平时
        // 是由各使用方（GPUHelper、Drawable、XInputStream 等）在自己的 static 块里加载的，
        // 那些类此时都还没被触碰。不显式加载会抛 UnsatisfiedLinkError，导致启动即崩。
        System.loadLibrary("winlator");
    }

    private static final String TAG = "CrashHandler";
    private static final String CRASH_LOG_FILE_NAME = "WinlatorCN-Crash.txt";
    // 放 Download 而非内部 filesDir：filesDir 在非 root 下取不出来，之前正是因此导致
    // 崩溃日志写了却读不到。放这里与 logcat 日志并列，直接就能导出来分析。
    private static final String NATIVE_CRASH_LOG_FILE_NAME = "WinlatorCN-NativeCrash.txt";
    private static final String LOGCAT_LOG_FILE_NAME = "WinlatorCN-logcat.txt";

    // logcat -v threadtime 的一行形如：09-06 12:34:56.789  1234  5678 D System.out: msg
    // group(1)=PID，group(2)=TAG；不含该前缀的行是多行日志的续行，沿用上一条的判定结果。
    private static final Pattern LOGCAT_LINE_PATTERN =
        Pattern.compile("^\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+(\\S.*?)\\s*:");

    // 捕获范围是全部进程（不能用 --pid：box64/wine 里的 guest so 日志会被整条滤掉），
    // 过滤规则：主进程 pid 的日志全保留（框架 TAG 如 ActivityManager 等不能丢），
    // 其它进程按 TAG 白名单放行，避免把系统与其它 App 的日志一起写进文件。
    private static final Set<String> LOGCAT_TAG_WHITELIST = new HashSet<>(Arrays.asList(
        "System.out",        // winlator / gladio / virglrenderer 的 println、debug_printf
        "hook_impl",         // libadrenotools
        "qtimapper-shim",
        "linkernsbypass",
        "CrashHandler",
        "WinHandler",
        "E02_KeyInput",
        "ExportContainer",
        "RestoreComponents",
        "RestoreContainer",
        "RestoreProfiles",
        "Symlink",
        "WineFolder",
        "AndroidRuntime"     // Java 崩溃（仅主进程会崩，pid 即主进程 pid，无需再进白名单特殊放行）
        // 注意：DEBUG / libc 不在白名单里放行 —— 崩溃 tombstone 由 crash_dump 进程写，
        // pid 与主进程不同。若像普通 tag 一样按 pid==myPid 或白名单放行，
        // 会把 crash buffer 里其它进程的旧崩溃记录一并写入。
        // 改为在过滤循环里按 “Fatal signal” 行的崩溃 pid 精确判定，只保留主进程自己的崩溃。
    ));

    private static File getCrashLogFile() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.isDirectory()) downloadsDir.mkdirs();
        return new File(downloadsDir, CRASH_LOG_FILE_NAME);
    }

    /**
     * native 崩溃日志独立成文件：与 Java 崩溃日志分开，避免互相覆盖——
     * Java 崩溃走 saveCrashLog 的覆盖写，会把 native 崩溃记录冲掉。
     * 路径与 logcat 日志同在 Download，便于取出（内部 filesDir 非 root 读不到）。
     */
    private static File getNativeCrashLogFile() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.isDirectory()) downloadsDir.mkdirs();
        return new File(downloadsDir, NATIVE_CRASH_LOG_FILE_NAME);
    }

    private static native void installNativeCrashHandler(String logFilePath);

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this, Thread.getDefaultUncaughtExceptionHandler()));
        // native 崩溃（gladio / vortek / virglrenderer 等主进程内 so 的 SIGSEGV/SIGABRT）
        // 不经过 JVM，Java 的 UncaughtExceptionHandler 完全感知不到，必须在 native 层接管。
        // 处理器写完日志会把信号交回 debuggerd，tombstone 照常生成。
        // 诊断设施绝不能拖垮启动：任何失败都只记日志，不向上抛。
        try {
            installNativeCrashHandler(getNativeCrashLogFile().getAbsolutePath());
        }
        catch (Throwable t) {
            Log.e(TAG, "Failed to install native crash handler", t);
        }
        // 先注入实际包名：容器默认 E: 盘路径按包名拼接，MT 改包共存后不能再用 com.winlator 的硬编码路径
        AppUtils.init(this);
        // MT 改包共存后 getPackageName() 为新包名，PatchUtils 据此决定是否替换解压产物中的宿主路径
        // (包名仍为原包名 com.winlator 时不启用，原版 APK 行为完全不变)
        File dataDir = getDataDir();
        PatchUtils.init(getPackageName(), dataDir);
        // 启用后解压产物里的 /data/data/com.winlator/files/rootfs 会被等长替换为
        // /data/data/<包名>/<别名>，需要在数据目录下建软链 <别名> -> files/rootfs，
        // 使替换后的路径仍解析到 rootfs。
        PatchUtils.ensureAliasSymlink(dataDir);
        // logcat 捕获不再于 Application 启动时自动开始：改为只覆盖容器会话
        // （XServerDisplayActivity 启动/退出时 start/stop），与 wine/box64 日志一致。
        // 主菜单、设置、容器安装等阶段不再记录，文件也不再被无关注重启清零。
    }

    /**
     * 容器会话开始：清空 logcat 日志并开始捕获。
     * 与 wine/box64 日志（LogView 在容器 Activity 创建时删除旧文件）保持同一生命周期。
     */
    public static void startLogcatSession(Context context) {
        if (!isLogcatCaptureEnabled(context)) return;
        Application app = (Application) context.getApplicationContext();
        startCapture(app, LOGCAT_LOG_FILE_NAME, false);
    }

    /** 容器会话结束：停止捕获，文件保留供查看。 */
    public static void stopLogcatSession() {
        stopCapture();
    }

    private static boolean isLogcatCaptureEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("save_logcat_to_file", false);
    }

    private static volatile Process captureProcess = null;
    private static volatile boolean captureStarting = false;

    private static void stopCapture() {
        Process process = captureProcess;
        captureProcess = null;
        captureStarting = false;
        if (process != null) {
            try {
                process.destroy();
            }
            catch (Exception ignored) {}
        }
    }

    /**
     * @param keepExisting true 追加到已有文件，false 清空重抓（容器会话开始时传 false）
     */
    private static void startCapture(final Application app, final String logFileName, final boolean keepExisting) {
        if (captureProcess != null || captureStarting) return;
        captureStarting = true;
        final File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), logFileName);
        final long maxSize = 20L * 1024 * 1024;
        final int myPid = android.os.Process.myPid();
        Thread thread = new Thread(() -> {
            try {
                // 每次容器会话开始都清空重抓：只保留本次容器运行的日志，与 wine/box64 日志
                // （LogView 在容器 Activity 创建时删除旧文件）保持一致的生命周期。
                // 已超过 maxSize 时同样清空，与下面循环内的轮转规则一致。
                boolean append = keepExisting && logFile.isFile() && logFile.length() <= maxSize;
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile, append), StandardCharsets.UTF_8)) {
                    writer.write("========== logcat capture started " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " ==========\n");
                    writer.write("filter: pid=" + myPid + " keep all, other processes tags = " + LOGCAT_TAG_WHITELIST + "\n");
                    writer.flush();
                }
                // -T 1 只从缓冲区最后一条开始输出，避免把上次运行的历史日志 dump 进新文件
                Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "threadtime", "-T", "1"});
                captureProcess = process;
                InputStream input = process.getInputStream();
                FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                boolean keepCurrentLine = false;
                boolean inOwnCrash = false; // 当前是否紧跟主进程(pid==myPid)的崩溃 tombstone 块
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("---------")) continue; // beginning of main/system 分隔行
                    Matcher matcher = LOGCAT_LINE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        int pid = Integer.parseInt(matcher.group(1));
                        String tag = matcher.group(2);
                        if ("libc".equals(tag) && line.contains("Fatal signal")) {
                            // 崩溃块开头。Android 崩溃 tombstone 第一行是 libc 的 Fatal signal，
                            // 该行的 pid 就是崩溃进程。仅当崩溃进程 == 主进程时才保留，
                            // 否则（crash buffer 里其他进程/历史崩溃）连后续 DEBUG 续行一起丢弃。
                            inOwnCrash = pid == myPid;
                            keepCurrentLine = inOwnCrash;
                        }
                        else if ("DEBUG".equals(tag)) {
                            // DEBUG tombstone 由 crash_dump 进程写，pid 不是主进程，
                            // 只能跟随 libc Fatal signal 行的判定结果，不能单独按 pid/白名单放行。
                            keepCurrentLine = inOwnCrash;
                        }
                        else {
                            // 常规日志：主进程全保留，其它进程按白名单放行。离开崩溃块。
                            inOwnCrash = false;
                            keepCurrentLine = pid == myPid || LOGCAT_TAG_WHITELIST.contains(tag);
                        }
                    }
                    // 无匹配（崩溃 tombstone 的多行续行，如寄存器 dump）沿用上一条的 keepCurrentLine
                    if (!keepCurrentLine) continue;

                    if (logFile.length() > maxSize) {
                        writer.flush();
                        writer.close();
                        try (OutputStreamWriter freshWriter = new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8)) {
                            freshWriter.write("========== logcat capture truncated at " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " ==========\n");
                            freshWriter.flush();
                        }
                        fos = new FileOutputStream(logFile, true);
                        writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                    }
                    writer.write(line);
                    writer.write("\n");
                    writer.flush();
                }
            }
            catch (Exception e) {
                Log.e(TAG, "Logcat capture failed", e);
            }
            finally {
                captureProcess = null;
                captureStarting = false;
            }
        }, "logcat-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private static class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Application application;
        private final Thread.UncaughtExceptionHandler prevHandler;

        private CrashHandler(Application application, Thread.UncaughtExceptionHandler prevHandler) {
            this.application = application;
            this.prevHandler = prevHandler;
        }

        @Override
        public void uncaughtException(final Thread thread, final Throwable throwable) {
            final boolean saved = saveCrashLog(thread, throwable);
            final String toastMessage = saved
                    ? application.getString(R.string.crash_log_saved, CRASH_LOG_FILE_NAME)
                    : application.getString(R.string.crash_log_save_failed);

            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> Toast.makeText(application, toastMessage, Toast.LENGTH_LONG).show());
            // 等 Toast 完整展示后再将崩溃交给原处理器/终止进程
            handler.postDelayed(() -> {
                if (prevHandler != null) prevHandler.uncaughtException(thread, throwable);
                else {
                    Log.e(TAG, "Uncaught exception", throwable);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }, 3500);
        }

        private boolean saveCrashLog(Thread thread, Throwable throwable) {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));

                StringBuilder sb = new StringBuilder();
                sb.append("========== ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append(" ==========\n");
                sb.append("App Version: ").append(getAppVersion()).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Thread: ").append(thread.getName()).append("\n\n");
                sb.append(sw.toString()).append("\n");

                File crashLogFile = getCrashLogFile();
                FileOutputStream fos = new FileOutputStream(crashLogFile, false);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                writer.write(sb.toString());
                writer.close();
                return true;
            }
            catch (Exception e) {
                Log.e(TAG, "Failed to write crash log", e);
                return false;
            }
        }

        private String getAppVersion() {
            try {
                PackageInfo pInfo = application.getPackageManager().getPackageInfo(application.getPackageName(), 0);
                return pInfo.versionName + " (" + pInfo.versionCode + ")";
            }
            catch (Exception e) {
                return "unknown";
            }
        }
    }
}
