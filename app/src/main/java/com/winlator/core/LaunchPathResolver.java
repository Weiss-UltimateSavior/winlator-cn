package com.winlator.core;

import android.net.Uri;

import com.winlator.container.Container;
import com.winlator.container.Drive;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
        public final String mountPath;
        public final boolean isDirectory;

        private Result(String unixPath, int error, String mountPath, boolean isDirectory) {
            this.unixPath = unixPath;
            this.error = error;
            this.mountPath = mountPath;
            this.isDirectory = isDirectory;
        }

        public boolean isSuccess() {
            return unixPath != null;
        }

        public boolean needsMount() {
            return mountPath != null;
        }
    }

    private static final Pattern DOS_PATH_PATTERN = Pattern.compile("^[A-Za-z]:.*");
    private static final String RESERVED_DRIVE_LETTERS = "CXZ";

    public static Result resolve(String input, Container container) {
        if (input == null) return error(ERROR_EMPTY);
        String path = input.trim();
        if (path.isEmpty()) return error(ERROR_EMPTY);
        if (path.startsWith("content://")) return error(ERROR_FORMAT);

        if (path.startsWith("file://")) {
            try {
                path = Uri.parse(path).getPath();
            }
            catch (Exception e) {
                path = null;
            }
            if (path == null || path.isEmpty()) return error(ERROR_FORMAT);
        }

        if (hasParentSegment(path)) return error(ERROR_FORMAT);

        if (DOS_PATH_PATTERN.matcher(path).matches()) {
            String unixPath = WineUtils.dosToUnixPath(path, container);
            return unixPath.isEmpty() ? error(ERROR_UNMAPPED) : new Result(unixPath, ERROR_NONE, null, false);
        }

        if (path.startsWith("/")) {
            if (isMappedUnixPath(path, container)) {
                if (!(new File(path)).exists()) return error(ERROR_NOT_FOUND);
                return new Result(path, ERROR_NONE, null, (new File(path)).isDirectory());
            }

            File file = new File(path);
            if (!file.exists()) return error(ERROR_NOT_FOUND);
            if (file.isDirectory()) return new Result(path, ERROR_NONE, path, true);

            File parent = file.getParentFile();
            if (parent == null) return error(ERROR_FORMAT);
            return new Result(path, ERROR_NONE, parent.getAbsolutePath(), false);
        }

        return error(ERROR_FORMAT);
    }

    public static boolean isDosPath(String path) {
        return path != null && DOS_PATH_PATTERN.matcher(path.trim()).matches();
    }

    public static String getDriveLetter(String dosPath) {
        return isDosPath(dosPath) ? dosPath.trim().substring(0, 1).toUpperCase(Locale.ENGLISH) : null;
    }

    public static boolean isReservedDriveLetter(String letter) {
        return letter == null || letter.length() != 1 || RESERVED_DRIVE_LETTERS.contains(letter);
    }

    public static boolean hasDriveLetter(Container container, String letter) {
        return hasDriveLetter(container != null ? container.getDrives() : null, letter);
    }

    public static boolean hasDriveLetter(String drives, String letter) {
        return getDrivePath(drives, letter) != null;
    }

    public static String getDrivePath(String drives, String letter) {
        if (drives == null || letter == null || letter.isEmpty()) return null;
        for (Drive drive : Container.drivesIterator(drives)) {
            if (drive.letter.equalsIgnoreCase(letter)) return drive.path;
        }
        return null;
    }

    public static String findDriveLetter(Container container, String path) {
        return findDriveLetter(container != null ? container.getDrives() : null, path);
    }

    public static String findDriveLetter(String drives, String path) {
        if (drives == null || path == null) return null;
        for (Drive drive : Container.drivesIterator(drives)) {
            if (drive.path != null && drive.path.equals(path)) return drive.letter.toUpperCase(Locale.ENGLISH);
        }
        return null;
    }

    public static String allocateDriveLetter(Container container) {
        return allocateDriveLetter(container != null ? container.getDrives() : null);
    }

    public static String allocateDriveLetter(String drives) {
        Set<String> used = new HashSet<>();
        if (drives != null) {
            for (Drive drive : Container.drivesIterator(drives)) used.add(drive.letter.toUpperCase(Locale.ENGLISH));
        }
        for (char letter = 'W'; letter >= 'F'; letter--) {
            String value = String.valueOf(letter);
            if (!used.contains(value) && !RESERVED_DRIVE_LETTERS.contains(value)) return value;
        }
        return null;
    }

    public static String appendDrive(String drives, String letter, String path) {
        return (drives != null ? drives : "")+letter+":"+path;
    }

    private static Result error(int error) {
        return new Result(null, error, null, false);
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
