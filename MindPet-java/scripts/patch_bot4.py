"""修补 start_bot.bat —— 由项目根目录 scripts/patch_bot4.py 运行"""
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BAT_PATH = os.path.join(SCRIPT_DIR, "..", "..", "start_bot.bat")

with open(BAT_PATH, 'rb') as f:
    raw = f.read()

text = raw.decode('gbk')

# Find the old ani-mcp block using ASCII markers
start_marker = 'REM ---'
end_marker = 'echo.\n\nREM ---'

start = text.find('REM --- 预装 npm')
if start < 0:
    start = text.find('REM --- Ԥװ npm')  # garbled gbk

end = text.find('echo.\n\nREM ---', start)
if end < 0:
    end = text.find('\n\necho.\n\nREM ---', start)

if start >= 0 and end >= 0:
    # Find the actual end of the old block (skip trailing newlines)
    while end < len(text) and text[end] in '\n\r':
        end += 1
    old = text[start:end]
    print(f'replacing {len(old)} bytes from pos {start}')

    new = (
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
        '\n'
        '\n'
    )
    text = text[:start] + new + text[end:]
else:
    print(f'not found: start={start}, end={end}')

# Update section numbers
text = text.replace('[3/4]', '[4/5]')

with open(BAT_PATH, 'wb') as f:
    f.write(text.encode('gbk'))
print('done')
