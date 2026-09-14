package com.winlator.core;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 解压产物硬编码路径替换工具。
 *
 * 设计目标：兼容"MT 改包名做共存"——运行时检测包名(getPackageName()，改包后为新包名)：
 *   - 等于原包名 com.winlator 时：不启用替换（原版 APK 行为完全不变）；
 *   - 不等于原包名时：仅对【白名单】内路径的解压文件做等长字节替换。
 *
 * 等长替换策略（包名长度 1~23 统一适用，不分长短包名、不补 0）：
 *   锚点   /data/data/com.winlator/files/rootfs  (36 字节)
 *   替换为 <宿主前缀>/<别名>                     (36 字节)
 *   别名长度 = 36 - 宿主前缀长度 - 1（宿主前缀为 /data/data/<新包名> 时即 24 - 包名长度，
 *   全 'a' 填充），由 App 在 <dataDir>/ 下建软链：
 *     /data/data/<新包名>/<别名>  ->  <dataDir>/files/rootfs
 *   使 guest 侧 /data/data/<包名>/<别名>/lib/... 解析到 rootfs/lib/...。
 *
 *   两点实测依据（扫描 assets 内全部 30 个压缩包得出）：
 *   1) 锚点不带尾斜杠：rootfs.tzst 内 445 处命中全部形如 /data/data/com.winlator/files/rootfs...，
 *      其中 2 处是"裸目录 + \0 结尾"(wine ntdll.so 的 build prefix、ld-linux-aarch64.so.1 的
 *      path.prefix)，锚点带尾斜杠会漏掉这两处；不带尾斜杠则两种形态都覆盖。
 *   2) 不能补 0：命中处后面都还接着路径内容，补 0 等于在 C 字符串中间截断，比不替换更糟；
 *      且 fonts.conf / alsa.conf / libc.so(ld script) / Makefile 等文本文件会被 NUL 污染。
 *
 *   包名过长(别名长度 < 1，即 /data/data/ 前缀下 > 23 字符)时无法等长容纳，
 *   跳过替换并打 warning（此时改包共存不可用）。
 *
 * 性能设计：
 *   - 白名单为"目录 -> 文件名集合"索引(HashMap + HashSet)，查询 O(1)；
 *   - 只有命中白名单的文件才被读入内存做字节替换；未命中文件零 IO。
 *
 * 挂接：TarCompressorUtils 解压落盘后，以【落盘绝对路径】(而非 tar 条目名)判断是否命中——
 *       tar 条目名常带 "./" 前缀(rootfs.tzst 等)且第三方 wine 包会被 listener 重映射到
 *       opt/installed-wine/ 下，用条目名匹配会整体漏判；用绝对路径则对命名风格免疫。
 */
public final class PatchUtils {
    private static final String TAG = "PatchUtils";

    /** 原包名：命中此包名视为原版，不做任何替换 */
    private static final String ORIGINAL_PACKAGE = "com.winlator";

    /** /data/data/<pkg> 是 /data/user/0/<pkg> 的软链，用短写法可多容纳 2 个包名字符 */
    private static final String DATA_DIR_PREFIX = "/data/data/";
    private static final String USER0_DIR_PREFIX = "/data/user/0/";

    /** 锚点整段: /data/data/com.winlator/files/rootfs (36 字符，不含尾斜杠) */
    private static final String ROOTFS_DIR = DATA_DIR_PREFIX + ORIGINAL_PACKAGE + "/files/rootfs";

    /** rootfs 在宿主侧的相对子路径 */
    private static final String ROOTFS_SUBPATH = "files/rootfs/";

    /** 别名(全 'a' 填充)；null 表示替换未启用 */
    private static volatile String aliasName = null;

    /** rootfs 绝对路径前缀(含结尾 '/')；/data/user/0 与 /data/data 两种等价写法都接受 */
    private static volatile String[] rootfsPrefixes = null;

    /** 预编译好的等长替换字节对(长度必然相等)；null 表示替换未启用 */
    private static volatile byte[] anchorBytes = null;
    private static volatile byte[] replaceBytes = null;

    /**
     * 白名单外的"动态目录"文件名集合：第三方 wine 解压于 opt/installed-wine/ 下、
     * 目录名不定无法静态枚举路径。但真正内嵌宿主路径段、需要替换的只有这几个
     * wine 核心二进制(与 rootfs 内 wine 相同)。命中该文件名才视为需要替换。
     */
    private static final Set<String> DYNAMIC_DIR_FILES = new HashSet<>(Arrays.asList(
        "nsiproxy.so",
        "ntdll.so",
        "wineserver"
    ));

    /** 白名单索引：父目录 -> 命中文件名集合（相对解压根路径） */
    private static final Map<String, Set<String>> WHITELIST = buildWhitelist();

    private static Map<String, Set<String>> buildWhitelist() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("etc/fonts/conf.dd", set("README"));
        map.put("etc/fonts", set("fonts.conf"));
        map.put("etc", set("ld.so.cache"));
        map.put("etc/pulse", set("client.conf"));
        map.put("opt/wine/bin", set("wineserver"));
        map.put("opt/wine/lib/wine/x86_64-unix", set("nsiproxy.so", "ntdll.so"));
        map.put("usr/bin", set("locale", "localedef"));
        map.put("usr/lib/alsa-lib", set("libasound_module_pcm_android_aserver.so"));
        map.put("usr/lib/getconf", set("POSIX_V6_LP64_OFF64", "POSIX_V7_LP64_OFF64", "XBS5_LP64_OFF64"));
        map.put("usr/lib/gstreamer-1.0", set("libgstaudioparsers.so", "libgstvideomixer.so", "libgstmpegpsdemux.so", "libgstfdkaac.so", "libgstvideorate.so", "libgstvideocrop.so", "libgstplayback.so", "libgstcompositor.so", "libgstrawparse.so", "libgsttypefindfunctions.so", "libgstmpg123.so", "libgstavi.so", "libgstvideoconvertscale.so", "libgstdeinterlace.so", "libgstmpeg2dec.so", "libgstaudioresample.so", "libgstapp.so", "libgstaudiomixer.so", "libgstvideofilter.so", "libgstapetag.so", "libgstencoding.so", "libgstasf.so", "libgstogg.so", "libgstcoreelements.so", "libgstwavparse.so", "libgstvideoparsersbad.so", "libgstisomp4.so", "libgstid3demux.so", "libgstvolume.so", "libgstlibav.so", "libgstaudiofx.so", "libgstvideobox.so", "libgstaudioconvert.so", "libgstmatroska.so"));
        map.put("usr/lib/krb5/plugins/kdb", set("db2.so"));
        map.put("usr/lib/krb5/plugins/preauth", set("otp.so", "test.so", "spake.so"));
        map.put("usr/lib/krb5/plugins/tls", set("k5tls.so"));
        map.put("usr/lib/mpg123", set("output_dummy.so", "output_oss.so"));
        map.put("usr/lib/pulseaudio", set("libpulsecommon-17.0.so", "libpulsedsp.so"));
        map.put("usr/lib", set("libGL.so.1.7.0", "libvulkan_freedreno.so", "libvulkan_vortek.so", "libxcb-shm.so.0.0.0", "libk5crypto.so.3.1", "libfontconfig.so.1.14.0", "libverto.so.0.0", "libasound.so.2.0.0", "libgsturidownloader-1.0.so.0.2501.0", "libgstcodecs-1.0.so.0.2501.0", "libffi.so.7.1.0", "libsyn123.so.0.2.3", "libgstadaptivedemux-1.0.so.0.2501.0", "libpulse.so.0.24.3", "libc.so", "libexpat.so.1.6.8", "libfreetype.so.6.20.2", "libgstplayer-1.0.so.0.2501.0", "libgstvideo-1.0.so.0.2501.0", "libnettle.so.8.9", "libgstapp-1.0.so.0.2501.0", "libgstisoff-1.0.so.0.2501.0", "libpulse-simple.so.0.1.1", "libxcb-randr.so.0.1.0", "libkadm5clnt_mit.so.12.0", "libXxf86vm.so.1.0.0", "libbrotlicommon.so.1.1.0", "libcom_err.so.3.0", "libmpeg2.so.0.1.0", "libkrb5support.so.0.1", "libhogweed.so.6.9", "libresolv.so.2", "libXrender.so.1.3.0", "libXi.so.6.1.0", "ld-linux-aarch64.so.1", "libgobject-2.0.so.0.8200.2", "libgnutlsxx.so.30.0.0", "libgstplay-1.0.so.0.2501.0", "libogg.so.0.8.5", "libatopology.so.2.0.0", "libgstsdp-1.0.so.0.2501.0", "libXrandr.so.2.2.0", "libdrm.so.2.123.0", "libbrotlidec.so.1.1.0", "libglib-2.0.so.0.8200.2", "libxcb-dri3.so.0.1.0", "libgstaudio-1.0.so.0.2501.0", "libgnutls.so.30.39.0", "libX11.so.6.4.0", "libgstphotography-1.0.so.0.2501.0", "libkdb5.so.10.0", "libgstbasecamerabinsrc-1.0.so.0.2501.0", "libgstbase-1.0.so.0.2501.0", "libgstsctp-1.0.so.0.2501.0", "libpulse-mainloop-glib.so.0.0.6", "libkrb5.so.3.3", "libout123.so.0.5.1", "libbrotlienc.so.1.1.0", "libXfixes.so.3.1.0", "libjpeg.so.8.3.2", "libXext.so.6.4.0", "libgstrtsp-1.0.so.0.2501.0", "libgstpbutils-1.0.so.0.2501.0", "libgstrtp-1.0.so.0.2501.0", "libgstcontroller-1.0.so.0.2501.0", "libc.so.6", "libxcb-present.so.0.0.0", "libgstcodecparsers-1.0.so.0.2501.0", "libvulkan.so.1.3.301", "libgstriff-1.0.so.0.2501.0", "libmpg123.so.0.48.2", "libz.so.1.3.1", "libgssrpc.so.4.2", "libnsl.so.1", "libXcursor.so.1.0.2", "libzstd.so.1.5.6", "libgstmpegts-1.0.so.0.2501.0", "libgstbadaudio-1.0.so.0.2501.0", "libnss_compat.so.2", "libkadm5srv_mit.so.12.0", "libkrad.so.0.0", "libgmodule-2.0.so.0.8200.2", "libgstanalytics-1.0.so.0.2501.0", "libXau.so.6.0.0", "libsndfile.so.1.0.37", "libgsttranscoder-1.0.so.0", "libgthread-2.0.so.0.8200.2", "libxcb-render.so.0.0.0", "libgsttag-1.0.so.0.2501.0", "libgssapi_krb5.so.2.2", "libgstwebrtc-1.0.so.0.2501.0", "libgstmse-1.0.so.0.2501.0", "libgio-2.0.so.0.8200.2", "libgstnet-1.0.so.0.2501.0", "libmpeg2convert.so.0.0.0", "libgstfft-1.0.so.0.2501.0", "libgirepository-2.0.so.0.8200.2", "libxcb-sync.so.1.0.0", "libgstinsertbin-1.0.so.0.2501.0", "libXcomposite.so.1.0.0", "libX11-xcb.so.1.0.0", "libgnutls-openssl.so.27.0.2", "libnss_db.so.2", "libxcb.so.1.1.0", "libpng16.so.16.44.0", "libgstallocators-1.0.so.0.2501.0", "libXdmcp.so.6.0.0", "libm.so", "libgstreamer-1.0.so.0.2501.0"));
        map.put("usr/local/bin", set("box64"));
        map.put("usr/share/alsa", set("alsa.conf"));
        map.put("usr/share/vulkan/icd.d", set("freedreno_icd.aarch64.json", "vortek_icd.aarch64.json"));
        map.put("var/db", set("Makefile"));
        return map;
    }

    private PatchUtils() {}

    private static Set<String> set(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /**
     * 注入运行时包名与数据目录，并据此算好等长替换所需的常量。
     * 在 Application.onCreate() 调用一次：PatchUtils.init(getPackageName(), getDataDir());
     *
     * 包名为原包名(原版 APK)、dataDir 缺失、或包名长到无法等长容纳时，替换保持未启用。
     */
    public static void init(String currentPackage, File dataDir) {
        if (currentPackage == null || currentPackage.isEmpty()) return;
        if (ORIGINAL_PACKAGE.equals(currentPackage) || dataDir == null) return;

        String dataPath = dataDir.getAbsolutePath();
        // /data/data/<pkg> 与 /data/user/0/<pkg> 等价(前者是后者的软链)，优先用短写法：
        // 宿主前缀每短 1 字符，可容纳的包名就多 1 字符。
        String hostPrefix = dataPath.startsWith(USER0_DIR_PREFIX) ? DATA_DIR_PREFIX + currentPackage : dataPath;

        // 等长约束: hostPrefix + "/" + alias == ROOTFS_DIR (36 字符)
        int aliasLen = ROOTFS_DIR.length() - hostPrefix.length() - 1;
        if (aliasLen < 1) {
            Log.w(TAG, "包名过长("+currentPackage.length()+" 字符)，无法等长替换宿主路径，改包共存不可用");
            return;
        }

        StringBuilder sb = new StringBuilder(aliasLen);
        for (int i = 0; i < aliasLen; i++) sb.append('a');

        rootfsPrefixes = hostPrefix.equals(dataPath)
            ? new String[]{dataPath + "/" + ROOTFS_SUBPATH}
            : new String[]{dataPath + "/" + ROOTFS_SUBPATH, hostPrefix + "/" + ROOTFS_SUBPATH};
        aliasName = sb.toString();
        anchorBytes = ROOTFS_DIR.getBytes(StandardCharsets.ISO_8859_1);
        replaceBytes = (hostPrefix + "/" + aliasName).getBytes(StandardCharsets.ISO_8859_1);
        Log.i(TAG, "宿主路径替换已启用: "+ROOTFS_DIR+" -> "+hostPrefix+"/"+aliasName);
    }

    /**
     * 创建别名软链：&lt;dataDir&gt;/&lt;alias&gt; -&gt; &lt;dataDir&gt;/files/rootfs。
     * 使解压产物中替换后的 /data/data/&lt;包名&gt;/&lt;alias&gt;/... 解析到 files/rootfs/...。
     *
     * 幂等：已存在且指向正确目标时直接返回。不能无条件重建——FileUtils.symlink 是"先删后建"
     * 且吞掉 ErrnoException，万一建失败就把原本正常的软链删没了。
     */
    public static void ensureAliasSymlink(File dataDir) {
        String alias = aliasName;
        if (alias == null || dataDir == null) return;

        File rootfsDir = new File(dataDir, "files/rootfs");
        File linkFile = new File(dataDir, alias);
        String target = rootfsDir.getAbsolutePath();

        if (FileUtils.isSymlink(linkFile)) {
            try {
                if (target.equals(Os.readlink(linkFile.getAbsolutePath()))) return;
            }
            catch (ErrnoException e) {}
        }

        FileUtils.symlink(target, linkFile.getAbsolutePath());
        if (!FileUtils.isSymlink(linkFile)) Log.w(TAG, "别名软链创建失败: "+linkFile.getAbsolutePath());
    }

    /**
     * 判断解压落盘后的文件是否需要替换。判据是【落盘绝对路径】而不是 tar 条目名：
     * 条目名在 rootfs.tzst / rootfs_patches / 多数显卡驱动包里带 "./" 前缀(白名单键不带)，
     * 且第三方 wine 包会被 listener 重映射到 opt/installed-wine/ 下(条目名仍是包内相对路径)，
     * 用条目名匹配会整体漏判；用绝对路径则对 tar 命名风格与重映射都免疫。
     *
     * 命中来源有两类：
     *   1. 白名单内：静态枚举的命中文件(相对 rootfs 的目录 -> 文件名)；
     *   2. 动态目录：第三方 wine 解压于 opt/installed-wine/ 下，仅三个核心二进制。
     * 不在 rootfs 下(container_pattern / dxwrapper / wincomponents / pulseaudio 等，实测 0 命中)
     * 或未命中白名单则返回 false，文件不读不做(零 IO)。
     */
    public static boolean needPatch(File file) {
        String[] prefixes = rootfsPrefixes;
        if (prefixes == null || file == null) return false;

        String path = file.getAbsolutePath();
        String rel = null;
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                rel = path.substring(prefix.length());
                break;
            }
        }
        if (rel == null || rel.isEmpty()) return false;

        // File.getAbsolutePath() 不消解 "." 段(实测 new File(dest,"./etc/x") 得到 "..././etc/x")，
        // 而 rootfs.tzst 等包的条目名都以 "./" 开头，不归一化则白名单键仍匹配不上。
        rel = stripDotSegments(rel);
        if (rel.isEmpty()) return false;

        int slash = rel.lastIndexOf('/');
        String fileName = slash >= 0 ? rel.substring(slash + 1) : rel;
        if (fileName.isEmpty()) return false;

        // 动态目录命中(第三方 wine 包，目录名不定)：仅三个 wine 核心二进制。
        if (rel.startsWith("opt/installed-wine/")) return DYNAMIC_DIR_FILES.contains(fileName);

        // 静态白名单命中(目录 -> 文件名)
        Set<String> names = WHITELIST.get(slash > 0 ? rel.substring(0, slash) : "");
        return names != null && names.contains(fileName);
    }

    /** 去掉路径中的 "." 段(前导 "./"、中间 "/./"、结尾 "/.")；无 "." 段时原样返回，避免额外分配 */
    private static String stripDotSegments(String path) {
        if (!path.startsWith("./") && path.indexOf("/.") < 0 && !path.equals(".")) return path;
        StringBuilder sb = new StringBuilder(path.length());
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    /**
     * 对单个命中的解压文件执行等长替换。锚点与替换串长度必然相等，因此不补 0、不改变文件长度
     * （补 0 会在路径中间插入 NUL 把 C 字符串截断，文本配置还会被污染）。
     * 替换未启用、文件不存在、文件过短、无命中时安全跳过；失败只记日志，不阻断解压流程。
     */
    public static void apply(File file) {
        byte[] anchor = anchorBytes;
        byte[] replace = replaceBytes;
        if (anchor == null || replace == null || file == null || !file.isFile()) return;

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long len = raf.length();
            if (len < anchor.length || len > Integer.MAX_VALUE) return;

            byte[] data = new byte[(int) len];
            raf.seek(0);
            raf.readFully(data);

            // 无命中(例如该文件本就无宿主路径、或已被替换过)则不回写
            if (!replaceAll(data, anchor, replace)) return;

            raf.seek(0);
            raf.write(data);
        }
        catch (IOException e) {
            Log.w(TAG, "替换失败: "+file.getAbsolutePath(), e);
        }
    }

    /** 等长就地替换全部命中，返回是否发生过替换。幂等：替换后锚点即消失，重复调用不再命中。 */
    private static boolean replaceAll(byte[] data, byte[] oldBytes, byte[] newBytes) {
        if (oldBytes.length != newBytes.length || oldBytes.length == 0) return false;
        boolean changed = false;
        int idx = indexOf(data, oldBytes, 0);
        while (idx >= 0) {
            System.arraycopy(newBytes, 0, data, idx, newBytes.length);
            changed = true;
            idx = indexOf(data, oldBytes, idx + oldBytes.length);
        }
        return changed;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        if (needle.length == 0) return -1;
        final int max = haystack.length - needle.length;
        outer:
        for (int i = Math.max(fromIndex, 0); i <= max; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}