#!/bin/bash
# 看 Custom service 节点原始 XML
cd /home/<USER>/.openclaw/workspace/jarvis/tmp
python3 << 'EOF'
xml = open('a1.xml', encoding='utf-8', errors='ignore').read()
idx = xml.find('Custom service')
if idx >= 0:
    # 打印该节点前后 1500 字符
    print(xml[max(0,idx-800):idx+1500])
else:
    print('not found')
EOF
