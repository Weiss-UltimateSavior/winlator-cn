package com.winlator.core;

import android.net.Uri;

import com.winlator.container.Container;
import com.winlator.container.Drive;

import java.io.File;
import java.util.regex.Pattern;

public abstract class LaunchPathResolver {
    public static final int ERROR_NONE = 0;
    public static final int ERROR_EMPTY = 1;
    public static final int ERROR_FORMAT = 2;
    public static final int ERROR_UNMAPPED = 3;
    public static final int ERROR_NOT_FOUND = 4;

    public static class Result {
        public final String unixPath;
        public final int error;

        private Result(String unixPath, int error) {
            this.unixPath = unixPath;
            this.error = error;
        }

        public boolean isSuccess() {
            return unixPath != null;
        }
    }

    private static final Pattern DOS_PATH_PATTERN = Pattern.compile("^[A-Za-z]:.*");

    public static Result resolve(String input, Container container) {
        if (input == null) return new Result(null, ERROR_EMPTY);
        String path = input.trim();
        if (path.isEmpty()) return new Result(null, ERROR_EMPTY);
        if (path.startsWith("content://")) return new Result(null, ERROR_FORMAT);

        if (path.startsWith("file://")) {
            try {
                path = Uri.parse(path).getPath();
            }
            catch (Exception e) {
                path = null;
            }
            if (path == null || path.isEmpty()) return new Result(null, ERROR_FORMAT);
        }

        if (hasParentSegment(path)) return new Result(null, ERROR_FORMAT);

        if (DOS_PATH_PATTERN.matcher(path).matches()) {
            String unixPath = WineUtils.dosToUnixPath(path, container);
            return unixPath.isEmpty() ? new Result(null, ERROR_UNMAPPED) : new Result(unixPath, ERROR_NONE);
        }

        if (path.startsWith("/")) {
            if (!isMappedUnixPath(path, container)) return new Result(null, ERROR_UNMAPPED);
            if (!(new File(path)).exists()) return new Result(null, ERROR_NOT_FOUND);
            return new Result(path, ERROR_NONE);
        }

        return new Result(null, ERROR_FORMAT);
    }

    private static boolean isMappedUnixPath(String unixPath, Container container) {
        if (container == null) return false;

        for (Drive drive : container.drivesIterator()) {
            if (drive.path == null || drive.path.isEmpty()) continue;
            String path = drive.path.endsWith("/") ? drive.path : drive.path+"/";
            if (unixPath.startsWith(path) || unixPath.equals(drive.path)) return true;
        }

        return unixPath.contains("/.wine/drive_c/");
    }

    private static boolean hasParentSegment(String path) {
        for (String segment : path.split("[/\\\\]")) {
            if (segment.equals("..")) return true;
        }
        return false;
    }
}
