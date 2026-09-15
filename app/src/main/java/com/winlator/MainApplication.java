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
    private static final String TAG = "CrashHandler";
    private static final String CRASH_LOG_FILE_NAME = "WinlatorCN-Crash.txt";
    private static final String LOGCAT_LOG_FILE_NAME = "WinlatorCN-logcat.txt";
    // app 自身触发进程重启前由 AppUtils.restartApplication 置位。新进程据此区分
    // "容器退出返回主菜单"与"用户从桌面冷启动"，前者保留已有 logcat 文件继续追加。
    public static final String LOGCAT_KEEP_ON_RESTART_PREF = "logcat_keep_on_restart";

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

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this, Thread.getDefaultUncaughtExceptionHandler()));
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
        // 用户从桌面冷启动 → 无标记 → 重置 logcat 文件；app 自身触发的进程重启
        // （容器退出返回主菜单、改设置、装 Wine）→ 有标记 → 保留已有日志继续追加。
        // 标记读完立即清除，保证下一次真正的冷启动仍会重置。
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        keepExistingLogcat = preferences.getBoolean(LOGCAT_KEEP_ON_RESTART_PREF, false);
        if (keepExistingLogcat) preferences.edit().remove(LOGCAT_KEEP_ON_RESTART_PREF).commit();
        startLogcatCapture();
    }

    // 按当前开关状态同步 logcat 捕获：幂等，可随时调用（设置页保存后、Activity 恢复时）
    // 冷启动时存储尚未就绪导致 startCapture 静默失败，靠界面恢复时重新同步来重试
    public static void syncLogcatCapture(Context context) {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("save_logcat_to_file", false);
        if (enabled) {
            if (captureProcess != null) return; // 已在运行
            Application app = (Application) context.getApplicationContext();
            startCapture(app, LOGCAT_LOG_FILE_NAME);
        }
        else {
            stopCapture();
        }
    }

    private static volatile Process captureProcess = null;
    private static volatile boolean captureStarting = false;
    // 本次进程是否保留已有 logcat 文件（追加而非清空）。onCreate 依据 restartApplication
    // 打的标记判定一次；首次启动捕获后置为 true，使同一进程内后续再次启动捕获（如设置页
    // 反复开关 logcat）也走追加，不丢掉本次会话已写下的日志。
    private static volatile boolean keepExistingLogcat = false;

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

    private void startLogcatCapture() {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("save_logcat_to_file", false);
        if (!enabled) return;
        startCapture(this, LOGCAT_LOG_FILE_NAME);
    }

    private static void startCapture(final Application app, final String logFileName) {
        if (captureProcess != null || captureStarting) return;
        captureStarting = true;
        final File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), logFileName);
        final long maxSize = 20L * 1024 * 1024;
        final int myPid = android.os.Process.myPid();
        Thread thread = new Thread(() -> {
            try {
                // 容器退出会走 XServerDisplayActivity.exit -> AppUtils.restartApplication -> Runtime.exit(0)，
                // 整个进程被杀后由 makeRestartActivityTask 拉起新进程，onCreate 会再次走到这里。
                // 若此时截断文件，丢掉的正好是"容器退出前"那段最关键的日志，所以这种由 app 自身
                // 触发的重启要追加（keepExistingLogcat 来自 restartApplication 置的标记）；
                // 只有用户从桌面冷启动 app 才重置文件。文件不存在或已超过 maxSize 时同样清空
                // (与下面循环内的轮转规则一致)，每段会话由这个带新 pid 的 header 分隔。
                boolean append = keepExistingLogcat && logFile.isFile() && logFile.length() <= maxSize;
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile, append), StandardCharsets.UTF_8)) {
                    writer.write("========== logcat capture started " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " ==========\n");
                    writer.write("filter: pid=" + myPid + " keep all, other processes tags = " + LOGCAT_TAG_WHITELIST + "\n");
                    writer.flush();
                }
                // 文件已成功打开并写下 header，本次进程的"重置"动作已完成。之后同一进程内
                // 再次启动捕获（设置页反复开关 logcat）一律追加，不丢本次会话日志。
                // 放在这里而非前面：冷启动时若存储未就绪导致打开失败，标记仍为 false，
                // MainActivity 的幂等重试才会继续走重置而不是追加。
                keepExistingLogcat = true;
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
