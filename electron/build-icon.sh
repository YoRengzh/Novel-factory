#!/bin/bash
# 从 icon-256x256.png 重新合成多尺寸 icon.ico（改完图标设计后跑一次）
cd "$(dirname "$0")"
python -c "
from PIL import Image
img = Image.open('icon-256x256.png').convert('RGBA')
img.save('icon.ico', format='ICO', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
import os; print('✅ icon.ico:', os.path.getsize('icon.ico'), 'bytes')
"
