package com.dsh.launcher;

import java.util.Locale;

public final class FsEntry {

    public String name = "";
    public String path = "";
    public String linkTarget = "";
    public boolean dir;
    public boolean link;
    public long size;
    public long mtime;

    public boolean hidden() {
        return name.startsWith(".");
    }

    public String glyph() {
        if (dir) {
            return "folder";
        }
        String e = Util.ext(name);
        if (e.length() == 0) {
            return "file";
        }
        switch (e) {
            case "png": case "jpg": case "jpeg": case "gif": case "webp":
            case "bmp": case "svg": case "heic": case "ico":
                return "image";
            case "mp3": case "wav": case "flac": case "ogg": case "m4a":
            case "aac": case "opus": case "amr":
                return "audio";
            case "mp4": case "mkv": case "avi": case "mov": case "webm": case "3gp":
                return "video";
            case "zip": case "tar": case "gz": case "tgz": case "7z": case "rar":
            case "xz": case "bz2": case "jar":
                return "archive";
            case "apk": case "apks": case "xapk": case "aab":
                return "apk";
            case "java": case "kt": case "js": case "mjs": case "cjs": case "ts":
            case "tsx": case "jsx": case "py": case "sh": case "bash": case "zsh":
            case "c": case "h": case "cpp": case "hpp": case "cc": case "go":
            case "rs": case "rb": case "php": case "pl": case "lua": case "swift":
            case "sql": case "css": case "scss": case "html": case "htm": case "vue":
            case "xml": case "yml": case "yaml": case "toml": case "json":
                return "code";
            case "txt": case "md": case "markdown": case "log": case "ini":
            case "conf": case "cfg": case "properties": case "csv":
                return "doc";
            default:
                return "file";
        }
    }

    public boolean isTextLike() {
        String g = glyph();
        if ("image".equals(g) || "audio".equals(g) || "video".equals(g)
                || "archive".equals(g) || "apk".equals(g)) {
            return false;
        }
        return true;
    }

    public String sizeText() {
        if (dir) {
            return "目录";
        }
        return Util.size(size);
    }

    public String metaText() {
        StringBuilder sb = new StringBuilder();
        sb.append(Util.time(mtime));
        if (!dir) {
            sb.append(" · ").append(Util.size(size));
        }
        if (link && linkTarget != null && linkTarget.length() > 0) {
            sb.append(" · → ").append(linkTarget);
        }
        return sb.toString();
    }

    public String tagText() {
        if (dir) {
            return "目录";
        }
        String e = Util.ext(name);
        return e.length() == 0 ? null : e.toUpperCase(Locale.US);
    }
}
