#!/usr/bin/env python3
"""从 .spv 文件生成 shaders.h（内嵌 SPIR-V 字节数组）。

被 compile_shaders.bat 调用。读取 shader_dir 下的 sprite.vert.spv / sprite.frag.spv /
sky.frag.spv，输出与历史格式一致的 shaders.h（4 字/行、同 include guard、同 extern "C"），
以确保 sprite 字节与仓库既有版本逐位一致（仅新增 sky_frag_spv）。缺失的 .spv 写占位注释。
"""
import os
import struct
import sys

# 与历史 shaders.h 逐位一致的格式常量
GUARD = 'XIANXIA_SPIRV_SHADERS_H'
WORDS_PER_LINE = 4


def emit(name: str, path: str, out) -> None:
    """按历史格式输出一个 .spv 为 `static const uint32_t name[] = {...};` + `size_t name_size`。"""
    if not os.path.exists(path):
        out.write("// %s not available\n" % os.path.basename(path))
        return
    with open(path, 'rb') as f:
        data = f.read()
    words = struct.unpack('I' * (len(data) // 4), data)
    out.write("static const uint32_t %s[] = {\n" % name)
    for i in range(0, len(words), WORDS_PER_LINE):
        # 与历史 shaders.h 逐位一致的分隔：词间为 `,    `（逗号 + 4 空格）
        chunk = ',    '.join('0x%08x' % w for w in words[i:i + WORDS_PER_LINE])
        out.write("    %s,\n" % chunk)
    out.write("};\n")
    out.write("static const size_t %s_size = %d;\n" % (name, len(data)))


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit("usage: gen_shaders_header.py <shader_dir>")
    # 容忍 cmd 传入时偶尔带引号/尾反斜杠：去引号 + 规范化
    shader_dir = os.path.normpath(sys.argv[1].strip('"'))
    out_path = os.path.join(shader_dir, 'shaders.h')

    with open(out_path, 'w', encoding='utf-8') as out:
        out.write("// Auto-generated SPIR-V shader bytecode\n")
        out.write("// Do not edit — recompile with: compile_shaders.bat\n\n")
        out.write("#ifndef %s\n#define %s\n\n" % (GUARD, GUARD))
        out.write("#include <stddef.h>\n#include <stdint.h>\n\n")
        out.write("#ifdef __cplusplus\nextern \"C\" {\n#endif\n\n")
        emit('sprite_vert_spv', os.path.join(shader_dir, 'sprite.vert.spv'), out)
        out.write("\n")
        emit('sprite_frag_spv', os.path.join(shader_dir, 'sprite.frag.spv'), out)
        out.write("\n")
        emit('sky_vert_spv', os.path.join(shader_dir, 'sky.vert.spv'), out)
        out.write("\n")
        emit('sky_frag_spv', os.path.join(shader_dir, 'sky.frag.spv'), out)
        out.write("\n#ifdef __cplusplus\n}\n#endif\n\n")
        out.write("#endif // %s\n" % GUARD)
    print("Generated", out_path)


if __name__ == '__main__':
    main()

