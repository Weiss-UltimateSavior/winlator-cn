package com.winlator.core;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.DXWrappers;
import com.winlator.winhandler.OnPreExecListener;
import com.winlator.winhandler.WinEnums;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;

import java.io.File;
import java.util.Locale;

public class Win32AppWorkarounds implements OnPreExecListener {
    private final short taskAffinityMask;
    private final short taskAffinityMaskWoW64;
    private final XServerDisplayActivity activity;
    private SaveMemoryTask saveMemoryTask;

    private interface Workaround {}

    private static class MultiWorkaround implements Workaround {
        private final Workaround[] list;

        public MultiWorkaround(Workaround... list) {
            this.list = list;
        }
    }

    private interface WindowWorkaround extends Workaround {
        void apply(Window window);
    }

    private interface EnvVarsWorkaround extends Workaround {
        void apply(EnvVars envVars);
    }

    private interface ScreenSizeWorkaround extends Workaround {
        String getValue();
    }

    private interface DXWrapperWorkaround extends Workaround {
        String getValue();
    }

    private interface WinComponentsWorkaround extends Workaround {
        void setValue(KeyValueSet wincomponents);
    }

    private interface PreExecWorkaround extends Workaround {
        boolean apply(String path);
    }

    public Win32AppWorkarounds(XServerDisplayActivity activity) {
        this.activity = activity;
        Container container = activity.getContainer();
        taskAffinityMask = (short)ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short)ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));
        activity.getWinHandler().setOnPreExecListener(this);
    }

    private void applyWorkaround(Workaround workaround) {
        if (workaround instanceof EnvVarsWorkaround) {
            ((EnvVarsWorkaround)workaround).apply(activity.getOverrideEnvVars());
        }
        else if (workaround instanceof ScreenSizeWorkaround) {
            activity.setScreenInfo(new ScreenInfo(((ScreenSizeWorkaround)workaround).getValue()));
        }
        else if (workaround instanceof DXWrapperWorkaround) {
            activity.setDXWrapper(((DXWrapperWorkaround)workaround).getValue());
        }
        else if (workaround instanceof WinComponentsWorkaround) {
            KeyValueSet wincomponents = new KeyValueSet(Container.DEFAULT_WINCOMPONENTS);
            ((WinComponentsWorkaround)workaround).setValue(wincomponents);
            activity.setWinComponents(wincomponents.toString());
        }
    }

    public void applyStartupWorkarounds(String className) {
        Workaround workaround = getWorkaroundFor(className);
        if (workaround == null) return;

        if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround)workaround).list) applyWorkaround(workaround2);
        }
        else applyWorkaround(workaround);
    }

    private void setProcessAffinity(Window window, int processAffinity) {
        int processId = window.getProcessId();
        String className = window.getClassName();
        WinHandler winHandler = activity.getWinHandler();

        if (className.equals("steam.exe")) return;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    public void applyWindowWorkarounds(Window window) {
        Workaround workaround = getWorkaroundFor(window.getClassName());
        if (workaround instanceof WindowWorkaround) {
            ((WindowWorkaround)workaround).apply(window);
        }
        else if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround) workaround).list) {
                if (workaround2 instanceof WindowWorkaround) {
                    ((WindowWorkaround)workaround2).apply(window);
                    break;
                }
            }
        }

        int windowGroup = window.getWMHintsValue(Window.WMHints.WINDOW_GROUP);
        boolean canApplyProcessAffinity = window.isRenderable() && !window.getClassName().isEmpty() && windowGroup == window.id;
        if (canApplyProcessAffinity) {
            int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;
            if (processAffinity != 0) setProcessAffinity(window, processAffinity);
        }
    }

    private Workaround getWorkaroundFor(String className) {
        String appIdentifier;
        if (className.startsWith("steam://")) {
            appIdentifier = className.substring(className.lastIndexOf("/") + 1);
        }
        else appIdentifier = className.toLowerCase(Locale.ENGLISH);
        final WinHandler winHandler = activity.getWinHandler();

        switch (appIdentifier) {
            case "sonicgenerations.exe":
            case "71340":
            case "valkyria.exe":
            case "294860":
                return (EnvVarsWorkaround) (envVars) -> envVars.put("WINEESYNC", "0");
            case "blacklist_game.exe":
            case "blacklist_dx11_game.exe":
                return (EnvVarsWorkaround) (envVars) -> envVars.put("WINEOVERRIDEAFFINITYMASK", taskAffinityMaskWoW64);
            case "fate.exe":
            case "psychotoxic.exe":
                return (ScreenSizeWorkaround) () -> "1024x768";
            case "ffxii_tza.exe":
                ScreenInfo screenInfo = activity.getScreenInfo();
                return (ScreenSizeWorkaround) () -> (screenInfo.width+4)+"x"+(screenInfo.height+4);
            case "chronocross_launcher.exe":
                return (WindowWorkaround) (window) -> {
                    window.attributes.setTransparent(true);
                    AppUtils.runDelayed(() -> {
                        winHandler.showWindow(window.getHandle(), WinEnums.SW_MINIMIZE);
                        winHandler.showWindow(window.getHandle(), WinEnums.SW_RESTORE);
                    }, 500);
                };
            case "dino.exe":
            case "dino2.exe":
            case "bof4.exe":
                return (WinComponentsWorkaround) (wincomponents) -> wincomponents.put("directshow", "1");
            case "discipl2.exe":
                return (DXWrapperWorkaround) () -> DXWrappers.WINED3D;
            case "cnc3.exe":
                return (PreExecWorkaround) (path) -> {
                    File executableDir = getExecutableDir(path);
                    File oldFile = new File(executableDir, "CNC3_english_1.10.SkuDef");
                    if (oldFile.isFile()) oldFile.renameTo(new File(executableDir, "CNC3_english_1.10.SkuDef.old"));
                    return false;
                };
            case "start.exe":
                return (WindowWorkaround) (window) -> {
                    if (!window.getName().contains("Easy Anti-Cheat Launch Error")) return;
                    runGameExecutable(window, null);
                };
            case "ff9_launcher.exe":
                return (WindowWorkaround) (window) -> AppUtils.runDelayed(() -> winHandler.bringToFront(window.getClassName(), window.getHandle()), 1000);
            case "launcher.exe":
                return (PreExecWorkaround) (path) -> runGameExecutable(null, path);
            case "steam.exe":
                return (PreExecWorkaround) (path) -> {
                    if (activity.getPreferences().getBoolean("save_mem_on_run_from_steam", true)) {
                        if (saveMemoryTask == null) saveMemoryTask = new SaveMemoryTask();
                        saveMemoryTask.start();
                    }
                    return false;
                };
            default:
                return null;
        }
    }

    private File getExecutableDir(String dosPath) {
        return new File(FileUtils.getDirname(WineUtils.dosToUnixPath(dosPath, activity.getContainer())));
    }

    private boolean runGameExecutable(Window window, String dosPath) {
        final String[] relativePaths = {"BorderlandsPreSequel.exe", "bin/DBXV2.exe"};

        WinHandler winHandler = activity.getWinHandler();
        if (window != null) dosPath = winHandler.getExecutablePath(window.getProcessId());
        File executableDir = getExecutableDir(dosPath);

        for (String relativePath : relativePaths) {
            if ((new File(executableDir, relativePath)).isFile()) {
                final String filename = dosPath.replace(FileUtils.getName(dosPath), relativePath.replace("/", "\\"));
                AppUtils.runDelayed(() -> winHandler.exec(filename, null), 500);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onPreExec(String path) {
        String className = FileUtils.getName(path);
        Workaround workaround = getWorkaroundFor(className);

        if (workaround instanceof PreExecWorkaround) {
            return ((PreExecWorkaround)workaround).apply(path);
        }
        else return false;
    }
}