"""SPIR-V 到 C 头文件转换脚本
用法: python3 gen_header.py [output_dir]
  默认输出到当前脚本所在目录
"""
import struct
import os
import sys

script_dir = os.path.dirname(os.path.abspath(__file__))
# 如果提供了输出目录参数则使用，否则输出到脚本目录
output_dir = sys.argv[1] if len(sys.argv) > 1 else script_dir

shaders = [
    (os.path.join(script_dir, 'sprite.vert.spv'), 'sprite_vert_spv'),
    (os.path.join(script_dir, 'sprite.frag.spv'), 'sprite_frag_spv'),
]
output = os.path.join(output_dir, 'shaders.h')

with open(output, 'w') as h:
    h.write('// Auto-generated SPIR-V shader bytecode\n')
    h.write('// Do not edit — recompile spv files to regenerate\n\n')
    h.write('#ifndef XIANXIA_SPIRV_SHADERS_H\n')
    h.write('#define XIANXIA_SPIRV_SHADERS_H\n\n')
    h.write('#include <stddef.h>\n')
    h.write('#include <stdint.h>\n\n')
    h.write('#ifdef __cplusplus\n')
    h.write('extern "C" {\n')
    h.write('#endif\n\n')

    for spv_file, var_name in shaders:
        if not os.path.exists(spv_file):
            print(f'WARNING: {spv_file} not found, skipping {var_name}')
            continue
        with open(spv_file, 'rb') as f:
            data = f.read()
        words = struct.unpack('I' * (len(data) // 4), data)
        h.write(f'// {os.path.basename(spv_file)} ({len(data)} bytes)\n')
        h.write(f'static const uint32_t {var_name}[] = {{\n')
        for i in range(0, len(words), 8):
            chunk = ', '.join(f'0x{w:08X}' for w in words[i:i+8])
            h.write(f'    {chunk},\n')
        h.write('};\n')
        h.write(f'static const size_t {var_name}_size = {len(data)};\n\n')

    h.write('#ifdef __cplusplus\n')
    h.write('}\n')
    h.write('#endif\n\n')
    h.write('#endif // XIANXIA_SPIRV_SHADERS_H\n')

print(f'Generated: {output}')
print(f'  sprite.vert.spv: {os.path.getsize(shaders[0][0])} bytes')
print(f'  sprite.frag.spv: {os.path.getsize(shaders[1][0])} bytes')
