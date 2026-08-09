"""修补 start_bot.bat —— 由项目根目录 scripts/patch_bot3.py 运行"""
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BAT_PATH = os.path.join(SCRIPT_DIR, "..", "..", "start_bot.bat")

with open(BAT_PATH, 'rb') as f:
    raw = f.read()

# Try to decode (gbk for Chinese Windows bat files)
try:
    text = raw.decode('gbk')
except:
    text = raw.decode('utf-8')

# 1. Replace the old ani-mcp/sharp pre-install block with HowToCook MCP startup
old_block = (
    'REM --- 预装 npm MCP 依赖 ---\n'
    'echo [*] 预装 npm MCP 依赖...\n'
    'where npm >nul 2>&1\n'
    'if not errorlevel 1 (\n'
    '    echo       安装 ani-mcp...\n'
    '    call npm install -g ani-mcp >nul 2>&1\n'
    '    echo       安装 sharp 依赖...\n'
    '    cd /d "%APPDATA%\\npm\\node_modules\\ani-mcp"\n'
    '    call npm install --include=optional sharp >nul 2>&1\n'
    '    cd /d "%~dp0"\n'
    '    echo       ani-mcp 已就绪\n'
    ') else (\n'
    '    echo       [警告] 未找到 npm，动漫工具不可用\n'
    ')\n'
)

new_block = (
    'REM --- 启动 HowToCook MCP Server (菜谱) ---\n'
    'echo [3/5] 启动 HowToCook 菜谱 MCP 服务...\n'
    'set COOK_RUNNING=0\n'
    'for /f "tokens=5" %%a in (\'netstat -ano ^| findstr ":3000.*LISTENING" 2^>nul\') do set COOK_RUNNING=1\n'
    'if !COOK_RUNNING! equ 1 (\n'
    '    echo       HowToCook MCP 已在运行，跳过启动\n'
    '    goto :skip_cook\n'
    ')\n'
    'where npx >nul 2>&1\n'
    'if errorlevel 1 (\n'
    '    echo       [警告] 未找到 npx，菜谱功能不可用\n'
    '    goto :skip_cook\n'
    ')\n'
    'start "HowToCook-MCP" /MIN npx -y howtocook-mcp --transport http --port 3000\n'
    'echo       HowToCook MCP 服务启动中 (http://localhost:3000)\n'
    'echo       等待 MCP 服务就绪...\n'
    'set cook_retry=0\n'
    ':wait_cook\n'
    'timeout /t 2 /nobreak >nul\n'
    'curl -s --max-time 3 http://localhost:3000/health >nul 2>&1\n'
    'if not errorlevel 1 goto :cook_ready\n'
    'set /a cook_retry+=1\n'
    'if !cook_retry! lss 10 goto :wait_cook\n'
    'echo       [警告] HowToCook MCP 超时未就绪\n'
    'goto :skip_cook\n'
    ':cook_ready\n'
    'echo       HowToCook MCP 服务已就绪!\n'
    ':skip_cook\n'
)

if old_block in text:
    text = text.replace(old_block, new_block)
    print('replaced ani-mcp block')
else:
    print('old block not found, searching...')
    idx = text.find('预装 npm MCP')
    if idx >= 0:
        print('found at', idx)
        print(repr(text[idx:idx+500]))
    else:
        print('not found at all')

# 2. Update section numbers
text = text.replace('[3/4] 启动 MindPet Bot...', '[4/5] 启动 MindPet Bot...')

# Write back
with open(BAT_PATH, 'wb') as f:
    f.write(text.encode('gbk'))
print('done')
