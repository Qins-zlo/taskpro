package io.taskpro;

/**
 * 脚本开发文档。App 内置, 支持 Markdown 格式渲染 (ScriptDocView)。
 * 涵盖: 基础模式(HTTP任务/Shell任务) / 完整版(脚本库 py·js·sh / 终端)
 * / 变量声明(安装弹窗) / 环境变量 / 定时调度 / 调试建议。
 */
public class ScriptDoc {
    public static final String DOC =
"# TaskPro 脚本开发文档\n" +
"\n" +
"> 定时任务 Pro · 内置 Node.js 26 / Python 3.14 / BusyBox 完整运行时\n" +
"> 支持 .js .mjs .py .sh 四种脚本 + HTTP 任务 + 内置终端调试\n" +
"\n" +
"---\n" +
"\n" +
"## 目录\n" +
"\n" +
"1. 快速开始\n" +
"2. 基础模式 (HTTP 任务 / Shell 任务)\n" +
"3. Node.js 脚本开发 (.js / .mjs)\n" +
"4. Python 脚本开发 (.py)\n" +
"5. Shell 脚本开发 (.sh)\n" +
"6. 变量声明 (安装弹窗)\n" +
"7. 环境变量\n" +
"8. 定时任务\n" +
"9. HTTP 常用提取正则\n" +
"10. 调试建议\n" +
"\n" +
"---\n" +
"\n" +
"## 1. 快速开始\n" +
"\n" +
"- 新建脚本: 脚本库 → 右下角 ＋ → 选模板\n" +
"- 运行脚本: 脚本卡片点「运行」立即执行\n" +
"- 定时执行: 脚本卡片点「定时」配置 Cron\n" +
"- 调试命令: 更多 → 终端 (与脚本同一运行环境)\n" +
"- 装包: 更多 → 运行时修复 → 安装依赖 (或脚本内 `npm install xxx` / `pip install xxx`)\n" +
"\n" +
"## 2. 基础模式\n" +
"\n" +
"### 2.1 HTTP 脚本 (任务)\n" +
"\n" +
"一串 HTTP 请求, 每个步骤一个请求 (登录→带凭证→签到), JSON 数组格式:\n" +
"\n" +
"```json\n" +
"[{\"method\":\"POST\",\n" +
"  \"url\":\"https://站点/api/user/login\",\n" +
"  \"headers\":{\"Content-Type\":\"application/json\"},\n" +
"  \"body\":\"{\\\"username\\\":\\\"账号\\\",\\\"password\\\":\\\"密码\\\"}\",\n" +
"  \"extract\":{\"token\":\"\\\"access_token\\\":\\\"([^\\\"]*)\\\"\"}},\n" +
" {\"method\":\"POST\",\n" +
"  \"url\":\"https://站点/api/user/checkin\",\n" +
"  \"headers\":{\"Content-Type\":\"application/json\",\n" +
"    \"Authorization\":\"Bearer {{token}}\"},\n" +
"  \"body\":\"{}\"}]\n" +
"\n" +
"- `method`: GET / POST / PUT / DELETE\n" +
"- `url`: 请求地址 (必填)\n" +
"- `headers`: 请求头对象 (可选)\n" +
"- `body`: 请求体文本 (POST 常用)\n" +
"- `extract`: 正则提取字段, 第一个捕获组存入变量\n" +
"- 后续步骤用 `{{变量名}}` 引用提取结果\n" +
"- Cookie 自动管理: Set-Cookie 自动保存并携带\n" +
"\n" +
"### 2.2 Shell 任务 (基础模式)\n" +
"\n" +
"直接填 sh 命令, 内置完整运行时:\n" +
"\n" +
"```sh\n" +
"curl -s https://www.baidu.com | busybox head -3\n" +
"busybox free\n" +
"node -e \"console.log('hello')\"\n" +
"```\n" +
"\n" +
"环境说明:\n" +
"\n" +
"- 命令自动重写, 无需关心可执行文件位置\n" +
"- busybox wget 自动转为 curl (https 支持)\n" +
"- /tmp 自动映射 App 私有目录 (可写)\n" +
"- 工作目录 = App 数据目录, 可写文件\n" +
"- 可引用环境变量 (更多 → 环境变量)\n" +
"- 每次执行日志自动记录\n" +
"\n" +
"---\n" +
"\n" +
"## 3. Node.js 脚本开发 (.js / .mjs)\n" +
"\n" +
"> 推荐首选! 零依赖, 内置 fetch/fs, 启动快, 适合网页请求和API交互。\n" +
"\n" +
"### 3.1 Hello World\n" +
"\n" +
"```js\n" +
"console.log(\"Hello TaskPro!\");\n" +
"```\n" +
"\n" +
"### 3.2 网络请求 (fetch) — 零依赖!\n" +
"\n" +
"```js\n" +
"// checkin.mjs 用 ESM (支持顶层 await)\n" +
"const r = await fetch(\"https://api.example.com/sign\", {\n" +
"  method: \"POST\",\n" +
"  headers: { \"Content-Type\": \"application/json\", \"Cookie\": process.env.COOKIE }\n" +
"});\n" +
"const data = await r.json();\n" +
"console.log(\"结果:\", data);\n" +
"```\n" +
"\n" +
"### 3.3 文件读写\n" +
"\n" +
"```js\n" +
"const fs = require(\"fs\");\n" +
"const data = { ok: true, count: 42 };\n" +
"fs.writeFileSync(\"result.json\", JSON.stringify(data));\n" +
"console.log(fs.readFileSync(\"result.json\", \"utf8\"));\n" +
"```\n" +
"\n" +
"### 3.4 读取环境变量\n" +
"\n" +
"```js\n" +
"const name = process.env.NAME;          // 必填\n" +
"const token = process.env.TOKEN || \"\"; // 选填\n" +
"console.log(name, token);\n" +
"```\n" +
"\n" +
"### 3.5 定时任务脚本示例 (签到)\n" +
"\n" +
"```js\n" +
"// 变量: COOKIE=京东Cookie(必填), PUSH_TOKEN=推送Token(选填)\n" +
"const cookie = process.env.COOKIE;\n" +
"const push = process.env.PUSH_TOKEN || \"\";\n" +
"\n" +
"const r = await fetch(\"https://api.example.com/sign\", {\n" +
"  headers: { \"Cookie\": `pt_key=${cookie}` }\n" +
"});\n" +
"console.log(\"签到结果:\", await r.text());\n" +
"```\n" +
"\n" +
"### 3.6 安装 npm 包\n" +
"\n" +
"```sh\n" +
"npm install axios cheerio\n" +
"```\n" +
"\n" +
"说明:\n" +
"- `.js` = CommonJS (require), `.mjs` = ESM (import / 顶层 await)\n" +
"- 内置 fetch / fs / https / path 等标准 API\n" +
"- 无 DOM, 纯 Node 环境\n" +
"\n" +
"---\n" +
"\n" +
"## 4. Python 脚本开发 (.py)\n" +
"\n" +
"### 4.1 Hello World\n" +
"\n" +
"```python\n" +
"print(\"Hello TaskPro!\")\n" +
"```\n" +
"\n" +
"### 4.2 网络请求 (requests)\n" +
"\n" +
"```python\n" +
"import requests\n" +
"\n" +
"r = requests.get(\"https://api.example.com/data\", timeout=10)\n" +
"print(\"状态码:\", r.status_code)\n" +
"print(\"内容:\", r.text[:200])\n" +
"```\n" +
"\n" +
"### 4.3 文件读写\n" +
"\n" +
"```python\n" +
"import json\n" +
"with open(\"result.json\", \"w\") as f:\n" +
"    json.dump({\"ok\": True, \"count\": 42}, f)\n" +
"with open(\"result.json\") as f:\n" +
"    data = json.load(f)\n" +
"print(data)\n" +
"```\n" +
"\n" +
"### 4.4 读取环境变量\n" +
"\n" +
"```python\n" +
"import os\n" +
"name = os.environ[\"NAME\"]\n" +
"token = os.environ.get(\"TOKEN\", \"\")\n" +
"print(name, token)\n" +
"```\n" +
"\n" +
"### 4.5 安装第三方库\n" +
"\n" +
"```sh\n" +
"pip install requests beautifulsoup4\n" +
"```\n" +
"\n" +
"---\n" +
"\n" +
"## 5. Shell 脚本开发 (.sh)\n" +
"\n" +
"### 4.1 Hello World\n" +
"\n" +
"```js\n" +
"console.log(\"Hello TaskPro!\");\n" +
"```\n" +
"\n" +
"### 4.2 网络请求 (fetch)\n" +
"\n" +
"```js\n" +
"// test.mjs 用 ESM (支持顶层 await)\n" +
"const r = await fetch(\"https://www.baidu.com\");\n" +
"console.log(\"HTTP\", r.status);\n" +
"console.log((await r.text()).slice(0, 200));\n" +
"```\n" +
"\n" +
"### 4.3 文件读写 (fs)\n" +
"\n" +
"```js\n" +
"const fs = require(\"fs\");\n" +
"\n" +
"// 写文件\n" +
"fs.writeFileSync(\"out.txt\", \"hello\");\n" +
"\n" +
"// 读文件\n" +
"console.log(fs.readFileSync(\"out.txt\", \"utf8\"));\n" +
"```\n" +
"\n" +
"### 4.4 读取环境变量\n" +
"\n" +
"```js\n" +
"const name = process.env.NAME;\n" +
"const token = process.env.TOKEN || \"\";\n" +
"console.log(name, token);\n" +
"```\n" +
"\n" +
"### 4.5 HTTPS 请求 + Cookie 手动管理\n" +
"\n" +
"```js\n" +
"const headers = { \"Content-Type\": \"application/json\",\n" +
"                  \"Cookie\": process.env.COOKIE || \"\" };\n" +
"const r = await fetch(\"https://api.example.com/sign\", { headers });\n" +
"console.log(await r.text());\n" +
"```\n" +
"\n" +
"说明:\n" +
"\n" +
"- `.js` = CommonJS 模块 (`require`), `.mjs` = ESM (`import` / 顶层 await)\n" +
"- 内置 fetch / fs / https / path 等标准 API\n" +
"- 无 DOM, 是纯 Node 环境\n" +
"\n" +
"---\n" +
"\n" +
"## 5. Shell 脚本开发 (.sh)\n" +
"\n" +
"### 5.1 Hello World\n" +
"\n" +
"```sh\n" +
"#!/system/bin/sh\necho \"Hello TaskPro!\"\n" +
"```\n" +
"\n" +
"### 5.2 网络请求 (curl)\n" +
"\n" +
"```sh\n" +
"curl -s -X POST https://api.example.com/login \\\n" +
"  -H \"Content-Type: application/json\" \\\n" +
"  -d '{\"user\":\"xxx\",\"pass\":\"yyy\"}'\n" +
"```\n" +
"\n" +
"### 5.3 读取环境变量\n" +
"\n" +
"```sh\n" +
"echo \"NAME=$NAME\"\n" +
"echo \"TOKEN=$TOKEN\"\n" +
"# 带默认值\n" +
"CRON=${CRON:-0 8 * * *}\n" +
"```\n" +
"\n" +
"### 5.4 常用循环 + 条件\n" +
"\n" +
"```sh\n" +
"for i in 1 2 3; do\n" +
"  echo \"第 $i 次\"\n" +
"done\n" +
"\n" +
"if [ -f result.log ]; then\n" +
"  echo \"有日志\"\n" +
"else\n" +
"  echo \"无日志\"\n" +
"fi\n" +
"```\n" +
"\n" +
"### 5.5 完整示例: 带变量 + 定时\n" +
"\n" +
"```sh\n" +
"# 变量: COOKIE=站长Cookie, API=接口地址\n" +
"curl -s -H \"Cookie: $COOKIE\" \"$API/sign\" | tee /tmp/sign.log\n" +
"echo \"完成, 退出码 $?\"\n" +
"```\n" +
"\n" +
"内置命令: curl / python3 / pip / node / busybox (df ps free sed head tail 等 100+)\n" +
"\n" +
"---\n" +
"\n" +
"## 6. 变量声明 (安装弹窗)\n" +
"\n" +
"> 这是「脚本市场」最实用的功能: 在脚本注释里声明变量,\n" +
"> 用户点击安装时**自动弹出配置表单**, 填写后保存,\n" +
"> 运行脚本时自动注入为环境变量, 脚本内直接读取。\n" +
"\n" +
"### 6.1 语法\n" +
"\n" +
"```python\n" +
"# 变量: 变量名=显示名称, 变量名2=显示名称2\n" +
"```\n" +
"\n" +
"或:\n" +
"\n" +
"```sh\n" +
"// 变量: 变量名=显示名称, 变量名2=显示名称2\n" +
"```\n" +
"\n" +
"### 6.2 规则\n" +
"\n" +
"- 位置: 脚本任意注释行, 建议文件顶部\n" +
"- 前缀: 行首 `//` 或 `#` (支持缩进)\n" +
"- 标识: 注释内容含 `变量:` 或 `变量：`\n" +
"- 分隔: 多个变量用 `,` 或 `，`\n" +
"- 格式: 每个 `key=label`, label 是展示给用户的说明 (可省略)\n" +
"\n" +
"### 6.3 自动密码遮罩\n" +
"\n" +
"变量名 (不区分大小写) 含以下关键词 → 输入框自动变密码模式:\n" +
"\n" +
"- `PWD` / `PASS` / `TOKEN` / `SECRET` / `APIKEY`\n" +
"\n" +
"示例: `TG_BOT_TOKEN` 会自动遮罩, `COOKIE` 不会。\n" +
"\n" +
"### 6.4 三种语言写法\n" +
"\n" +
"**Python:**\n" +
"\n" +
"```python\n" +
"# 变量: JD_COOKIE=京东Cookie, TG_TOKEN=Telegram Token\n" +
"import os\n" +
"cookie = os.environ[\"JD_COOKIE\"]\n" +
"token = os.environ[\"TG_TOKEN\"]\n" +
"```\n" +
"\n" +
"**Node.js:**\n" +
"\n" +
"```js\n" +
"// 变量: JD_COOKIE=京东Cookie, TG_TOKEN=Telegram Token\n" +
"const cookie = process.env.JD_COOKIE;\n" +
"const token = process.env.TG_TOKEN;\n" +
"```\n" +
"\n" +
"**Shell:**\n" +
"\n" +
"```sh\n" +
"# 变量: JD_COOKIE=京东Cookie, TG_TOKEN=Telegram Token\n" +
"echo \"$JD_COOKIE\"\n" +
"echo \"$TG_TOKEN\"\n" +
"```\n" +
"\n" +
"### 6.5 用户安装流程\n" +
"\n" +
"上传市场 → 用户点安装 → 弹出配置表单:\n" +
"\n" +
"```\n" +
"┌─ 填写配置: qiandao.py ────────┐\n" +
"│ 京东Cookie                     │\n" +
"│ ┌────────────────────────┐    │\n" +
"│ │                        │    │\n" +
"│ └────────────────────────┘    │\n" +
"│ Telegram Token                │\n" +
"│ ┌────────────────────────┐    │\n" +
"│ │ ●●●●●●●●              │    │ ← 自动遮罩\n" +
"│ └────────────────────────┘    │\n" +
"│       [取消]  [保存并安装]     │\n" +
"└────────────────────────────────┘\n" +
"```\n" +
"\n" +
"填写 → 保存为 `{脚本名}.conf.json` → 运行自动注入环境变量。\n" +
"\n" +
"### 6.6 完整示例: 京东签到脚本\n" +
"\n" +
"```python\n" +
"# 变量: JD_COOKIE=京东Cookie(必填), PUSH_TOKEN=推送Token(选填)\n" +
"# 变量: CRON=定时表达式(默认 0 8 * * *)\n" +
"import os, requests\n" +
"\n" +
"cookie = os.environ[\"JD_COOKIE\"]\n" +
"push = os.environ.get(\"PUSH_TOKEN\", \"\")\n" +
"cron = os.environ.get(\"CRON\", \"0 8 * * *\")\n" +
"\n" +
"r = requests.get(\"https://api.m.jd.com/client.action\",\n" +
"                 cookies={\"pt_key\": cookie}, timeout=10)\n" +
"print(\"签到:\", r.text[:100])\n" +
"```\n" +
"\n" +
"---\n" +
"\n" +
"## 7. 环境变量\n" +
"\n" +
"- 全局配置: 更多 → 环境变量 (执行所有脚本时自动注入)\n" +
"- 脚本变量: 安装脚本时填写, 存为脚本专属配置\n" +
"- 优先级: 脚本配置 > 全局环境变量\n" +
"- 敏感信息 (密码/Token/Cookie) 建议用脚本变量, 不要硬编码\n" +
"\n" +
"```sh\n" +
"# 查看当前注入的环境变量 (终端执行)\n" +
"env | sort\n" +
"```\n" +
"\n" +
"---\n" +
"\n" +
"## 8. 定时任务\n" +
"\n" +
"### 8.1 配置方法\n" +
"\n" +
"- 脚本卡片 → 定时 → 填写 Cron 表达式\n" +
"- 主界面任务列表 → 添加任务 → 选脚本\n" +
"\n" +
"### 8.2 Cron 语法\n" +
"\n" +
"```\n" +
"分 时 日 月 周\n" +
"```\n" +
"\n" +
"- `0 8 * * *`        每天 08:00\n" +
"- `*/30 * * * *`     每 30 分钟\n" +
"- `0 9 * * 1`        每周一 09:00\n" +
"- `0 0 1 * *`        每月 1 号 00:00\n" +
"\n" +
"### 8.3 后台保活\n" +
"\n" +
"- 开启「后台常驻」提升定时可靠性\n" +
"- 日志页可查看每次定时执行结果\n" +
"\n" +
"---\n" +
"\n" +
"## 9. HTTP 常用提取正则\n" +
"\n" +
"```json\n" +
"取 token:  {\"token\": \"\\\"access_token\\\":\\\"([^\\\"]*)\\\"\"}\n" +
"取用户ID: {\"uid\": \"\\\"id\\\":([0-9]+)\"}\n" +
"取CSRF:   {\"csrf\": \"name=\\\"token\\\" value=\\\"([^\\\"]*)\\\"\"}\n" +
"```\n" +
"\n" +
"捕获组1 = 变量值, 后续步骤用 `{{变量名}}` 引用。\n" +
"\n" +
"---\n" +
"\n" +
"## 10. 调试建议\n" +
"\n" +
"1. 添加任务后点「立即执行」查看结果\n" +
"2. 「日志」查看每一步状态码和输出\n" +
"3. 终端逐步调试: 更多 → 终端\n" +
"4. 分析网站请求: 浏览器 F12 或抓包\n" +
"5. 脚本报错先看完整日志 (stderr 带 `!` 前缀)\n" +
"6. 缺失 Python 包 → 更多 → 运行时修复 → 安装依赖\n" +
"\n" +
"---\n" +
"\n" +
"祝你开发愉快! 有问题先看日志, 日志会告诉你每一步发生了什么 \n";
}