package com.dsh.launcher;

/**
 * 纯逻辑单测：路径换算 / 显示格式化 / 导出目标计算 / 图标映射。
 * 这些函数不碰任何 Android 运行时 API，所以可以直接在 JVM 上跑，
 * 用 tools/run-logic-test.sh 执行（不需要模拟器、不需要设备）。
 */
public final class LogicTest {

    private static int pass;
    private static int fail;

    private static void eq(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  ✓ " + what);
        } else {
            fail++;
            System.out.println("  ✗ " + what + "  期望[" + expected + "] 实际[" + actual + "]");
        }
    }

    private static final String WS = "/data/data/com.termux/files/home/usr/DSH";
    private static final String SHARED = "/sdcard/Download/DSH-Workspace";

    public static void main(String[] args) {
        System.out.println("[1] 路径工具");
        eq("join 普通", "/a/b", Util.join("/a", "b"));
        eq("join 尾斜杠", "/a/b", Util.join("/a/", "b"));
        eq("parent 深路径", "/a/b", Util.parent("/a/b/c"));
        eq("parent 单层", "/", Util.parent("/a"));
        eq("parent 根", "/", Util.parent("/"));
        eq("parent 尾斜杠", "/a", Util.parent("/a/b/"));
        eq("baseName", "c.md", Util.baseName("/a/b/c.md"));
        eq("baseName 尾斜杠", "c.md", Util.baseName("/a/b/c.md/"));
        eq("ext", "md", Util.ext("a.md"));
        eq("ext 无", "", Util.ext("README"));
        eq("ext 隐藏文件", "", Util.ext(".gitignore"));
        eq("isShared /sdcard", Boolean.TRUE, Util.isShared("/sdcard/Download"));
        eq("isShared /storage", Boolean.TRUE, Util.isShared("/storage/emulated/0/DCIM"));
        eq("isShared termux", Boolean.FALSE, Util.isShared(WS + "/a"));
        eq("isShared 相似前缀", Boolean.FALSE, Util.isShared("/sdcardx/a"));

        System.out.println("[2] 体积格式化");
        eq("0", "0 B", Util.size(0));
        eq("1023", "1023 B", Util.size(1023));
        eq("1024", "1.0 KB", Util.size(1024));
        eq("2048", "2.0 KB", Util.size(2048));
        eq("MB", "1.5 MB", Util.size(1572864L));
        eq("GB", "2.0 GB", Util.size(2147483648L));
        eq("大数取整", "500 KB", Util.size(512000L));

        System.out.println("[3] 导出目标换算");
        eq("工作区内文件", SHARED + "/sub/a.txt",
                FsClient.exportDest(WS + "/sub/a.txt", WS, SHARED));
        eq("工作区根", SHARED, FsClient.exportDest(WS, WS, SHARED));
        eq("工作区外文件", SHARED + "/_external/x.txt",
                FsClient.exportDest("/tmp/x.txt", WS, SHARED));
        eq("带尾斜杠的根", SHARED + "/sub/a.txt",
                FsClient.exportDest(WS + "/sub/a.txt", WS + "/", SHARED + "/"));
        eq("空共享目录回落默认", Prefs.DEF_SHARED + "/sub/a.txt",
                FsClient.exportDest(WS + "/sub/a.txt", WS, ""));

        System.out.println("[4] 文件类型 -> 图标");
        eq("目录", "folder", entry("docs", true).glyph());
        eq("md", "doc", entry("a.md", false).glyph());
        eq("java", "code", entry("A.java", false).glyph());
        eq("png", "image", entry("a.png", false).glyph());
        eq("mp3", "audio", entry("a.mp3", false).glyph());
        eq("mp4", "video", entry("a.mp4", false).glyph());
        eq("zip", "archive", entry("a.zip", false).glyph());
        eq("apk", "apk", entry("a.apk", false).glyph());
        eq("无扩展名", "file", entry("LICENSE", false).glyph());
        eq("未知扩展名", "file", entry("a.xyz", false).glyph());

        System.out.println("[5] 条目属性");
        eq("隐藏文件", Boolean.TRUE, entry(".env", false).hidden());
        eq("普通文件", Boolean.FALSE, entry("env", false).hidden());
        eq("目录 size 文案", "目录", entry("d", true).sizeText());
        eq("tag 大写", "MD", entry("a.md", false).tagText());
        eq("tag 无扩展名", null, entry("LICENSE", false).tagText());
        eq("文本可编辑", Boolean.TRUE, entry("a.md", false).isTextLike());
        eq("图片不可编辑", Boolean.FALSE, entry("a.png", false).isTextLike());

        System.out.println();
        System.out.println("===== 通过 " + pass + " / 失败 " + fail + " =====");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static FsEntry entry(String name, boolean dir) {
        FsEntry e = new FsEntry();
        e.name = name;
        e.dir = dir;
        e.size = 1024;
        e.mtime = System.currentTimeMillis() - 60_000L;
        return e;
    }
}
