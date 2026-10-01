#!/usr/bin/env python3
"""校验 APK 的 classes.dex 里真正「定义」了哪些类，并断言 AndroidManifest 声明的类都在。

为什么需要这个检查：
  Provider / Application / Activity 是类名写在清单里的「运行期引用」，
  aapt2 不解析它们，javac 也看不见它们。少一个类就是启动即闪退 ——
  而且如果缺的是 Provider，它在 Application.onCreate **之前**初始化，
  连崩溃捕获都来不及装，现象就是「点开闪一下就没了」，一点线索都没有。

用法： python3 tools/dex-check.py <apk> [AndroidManifest.xml]
"""
import re
import struct
import sys
import zipfile


def dex_defs(data):
    """解析 dex 的 class_defs，返回被『定义』的类描述符（不是被引用的）。"""
    (s_size, s_off, t_size, t_off, p_size, p_off, f_size, f_off,
     m_size, m_off, c_size, c_off) = struct.unpack_from('<12I', data, 56)

    def u32(o):
        return struct.unpack_from('<I', data, o)[0]

    def string(idx):
        off = u32(s_off + idx * 4)
        n = 0
        shift = 0
        p = off
        while True:
            b = data[p]
            p += 1
            n |= (b & 0x7f) << shift
            if b < 0x80:
                break
            shift += 7
        end = data.index(b'\x00', p)
        return data[p:end].decode('utf-8', 'replace')

    def type_desc(idx):
        return string(u32(t_off + idx * 4))

    out = []
    for i in range(c_size):
        out.append(type_desc(u32(c_off + i * 32)))
    return out


ANDROID_NS = '{http://schemas.android.com/apk/res/android}'
COMPONENT_TAGS = ('application', 'activity', 'activity-alias', 'service', 'receiver', 'provider')


def collect_components(manifest_path, default_pkg='com.dsh.launcher'):
    """只取组件标签上的 android:name（权限名 / package 名 / meta-data 名都不是类）。"""
    import xml.etree.ElementTree as ET
    root = ET.parse(manifest_path).getroot()
    pkg = root.get('package') or default_pkg
    out = []
    for el in root.iter():
        tag = el.tag.split('}')[-1]
        if tag not in COMPONENT_TAGS:
            continue
        name = el.get(ANDROID_NS + 'name')
        if not name:
            continue
        if name.startswith('.'):
            fqn = pkg + name
        elif '.' not in name:
            fqn = pkg + '.' + name
        else:
            fqn = name
        out.append((tag, fqn))
    return out


def main():
    if len(sys.argv) < 2:
        print('用法: dex-check.py <apk> [AndroidManifest.xml]')
        return 2
    apk = sys.argv[1]
    z = zipfile.ZipFile(apk)
    names = [n for n in z.namelist() if re.match(r'classes\d*\.dex$', n)]
    defs = set()
    for n in sorted(names):
        defs.update(dex_defs(z.read(n)))
    print('APK: %s' % apk)
    print('dex 文件: %s | 已定义类: %d 个' % (', '.join(sorted(names)), len(defs)))

    required = []
    if len(sys.argv) > 2:
        required = collect_components(sys.argv[2])

    bad = 0
    for tag, fqn in required:
        desc = 'L' + fqn.replace('.', '/') + ';'
        ok = desc in defs
        note = 'dex 中已定义'
        if not ok:
            note = 'dex 中缺失 → 启动即崩'
            if tag == 'provider':
                note += '（Provider 先于 Application.onCreate 实例化，崩溃捕获都来不及装）'
        print('  %s <%-10s> %-46s %s' % ('✓' if ok else '✗', tag, fqn, note))
        if not ok:
            bad += 1
    if bad:
        print('\n结果: ✗ %d 个清单类在 dex 中不存在' % bad)
        return 1
    print('\n结果: ✓ 清单声明的类全部已在 dex 中定义')
    return 0


if __name__ == '__main__':
    sys.exit(main())
