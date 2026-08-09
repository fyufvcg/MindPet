"""米家登录 —— 用米家APP扫码

依赖安装: pip install mijiaAPI
"""
import sys
import os

# 尝试导入，如果失败则给出提示
try:
    from mijiaAPI import mijiaAPI
except ImportError:
    print("错误: 未安装 mijiaAPI")
    print("请运行: pip install mijiaAPI")
    print("")
    print("如果已安装但仍报错，请检查 Python 环境是否正确")
    sys.exit(1)

api = mijiaAPI()
print("正在获取登录二维码...")
result = api.login()
print(result if result else "请在浏览器打开返回的链接扫码")

# 检查认证文件
auth = os.path.expanduser("~/.config/mijia-api/auth.json")
if os.path.exists(auth):
    print("✅ 登录成功")
else:
    print("⚠ 请扫码后重新运行此脚本确认")
