package com.dsh.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

/**
 * 「用其他文件管理器打开所在位置」。
 *
 * 两个现实约束决定了实现方式：
 *  1. 共享存储（/sdcard）里的路径 -> 用 DocumentsProvider 的 content:// URI 直接定位，
 *     这是 Android 官方姿势，任何实现了文档协议的文件管理器（系统「文件」/ MT管理器 / 质感文件）都能接。
 *  2. Termux 私有目录（/data/data/com.termux/...）-> 别的 App 的 UID 读不到，
 *     必须先把目录镜像到共享存储，再打开镜像位置。
 */
public final class OpenWith {

    private OpenWith() {
    }

    /** /sdcard/xxx -> content://com.android.externalstorage.documents/document/primary%3Axxx */
    public static Uri docUri(String sharedPath) {
        String rel = sharedPath == null ? "" : sharedPath;
        if (rel.startsWith("/sdcard/")) {
            rel = rel.substring("/sdcard/".length());
        } else if (rel.startsWith("/storage/emulated/0/")) {
            rel = rel.substring("/storage/emulated/0/".length());
        } else if (rel.equals("/sdcard") || rel.equals("/storage/emulated/0")) {
            rel = "";
        } else if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        while (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }
        String docId = rel.length() == 0 ? "primary:" : "primary:" + rel;
        return Uri.parse("content://com.android.externalstorage.documents/document/"
                + Uri.encode(docId));
    }

    /** 打开共享存储中的某个位置（目录或文件），交给用户选文件管理器。 */
    public static boolean openShared(Activity act, String sharedPath, boolean dir, String fileName) {
        String type = dir ? DocumentsContract.Document.MIME_TYPE_DIR
                : Util.mimeOf(fileName == null ? sharedPath : fileName);
        if (tryView(act, docUri(sharedPath), type)) {
            return true;
        }
        if (tryView(act, docUri(sharedPath), "*/*")) {
            return true;
        }
        return tryFileUri(act, sharedPath, type);
    }

    /** 直接把 Termux 私有路径丢出去（无 root 时多半打不开，作为兜底选项）。 */
    public static boolean tryFileUri(Activity act, String path, String type) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("file://" + path), type == null ? "*/*" : type);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(i, act.getString(R.string.open_with_title));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(chooser);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryView(Activity act, Uri uri, String type) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, type);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(i, act.getString(R.string.open_with_title));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(chooser);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
