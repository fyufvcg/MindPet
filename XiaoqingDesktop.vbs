Option Explicit

Dim shell, fileSystem, processService
Dim scriptPath, rootDir, backendDir, appDir, backendJar
Dim logDir, logPath, healthUrl, exitCode, attempt, electronSeen

Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")

scriptPath = fileSystem.GetAbsolutePathName(WScript.ScriptFullName)
rootDir = fileSystem.GetParentFolderName(scriptPath)
backendDir = fileSystem.BuildPath(rootDir, "MindPet-java")
appDir = fileSystem.BuildPath(rootDir, "MindPet")
backendJar = fileSystem.BuildPath(backendDir, "target\weather-wechat-bot-1.0.0.jar")
logDir = shell.ExpandEnvironmentStrings("%LOCALAPPDATA%")
If Len(logDir) = 0 Or InStr(logDir, "%LOCALAPPDATA%") > 0 Then
    logDir = shell.ExpandEnvironmentStrings("%TEMP%")
End If
logDir = fileSystem.BuildPath(fileSystem.BuildPath(logDir, "MindPet"), "logs")
logPath = fileSystem.BuildPath(logDir, "dev-launcher.log")
healthUrl = shell.ExpandEnvironmentStrings("%MINDPET_BACKEND_URL%")
If Len(healthUrl) = 0 Or InStr(healthUrl, "%MINDPET_BACKEND_URL%") > 0 Then
    healthUrl = "http://127.0.0.1:8080"
End If
Do While Right(healthUrl, 1) = "/"
    healthUrl = Left(healthUrl, Len(healthUrl) - 1)
Loop
healthUrl = healthUrl & "/api/desktop/health"

EnsureFolder logDir

On Error Resume Next
Set processService = GetObject("winmgmts:\\.\root\cimv2")
If Err.Number <> 0 Then
    Dim processServiceError
    processServiceError = Err.Description
    Err.Clear
    On Error GoTo 0
    Fail "进程检查", "无法读取 Windows 进程列表：" & processServiceError
End If
On Error GoTo 0

' Check the running application before touching the backend JAR. This prevents
' a second double-click from stopping the backend owned by the active app.
If IsWorkspaceDevRunning() Then
    LogLine "MindPet is already running; no files or processes were changed."
    MsgBox "MindPet 开发版已经在运行。" & vbCrLf & vbCrLf & _
        "本次启动未重新构建，也没有终止正在运行的后端。", _
        vbInformation, "MindPet"
    WScript.Quit 0
End If

If IsAnotherLauncherRunning() Then
    LogLine "Another launcher instance is already preparing MindPet."
    MsgBox "MindPet 正在启动，请稍候。" & vbCrLf & vbCrLf & _
        "为避免重复构建，本次操作已经取消。", _
        vbInformation, "MindPet"
    WScript.Quit 0
End If

ResetLog
LogLine "MindPet local SQLite development launcher"
LogLine "Workspace: " & rootDir

' The old batch entry kept a console attached to the development server. Its
' eventual close looked like a crash even when Electron had started normally.
' This WSH entry keeps every build command hidden; progress is written to the
' log and any actionable failure is shown in a native dialog.
LogLine "The launcher UI is intentionally console-free. Build commands run hidden."

CheckTool "java", "Java 21 JDK"
CheckTool "mvn", "Maven"
CheckTool "node", "Node.js 20+"
CheckTool "npm", "npm"

LogLine "[1/3] Stopping an old backend that uses this workspace JAR."
StopOldBackend

LogLine "[1/3] Building the SQLite backend."
exitCode = RunLogged("cd /d " & Quote(backendDir) & " && call mvn -q -DskipTests clean package", True)
If exitCode <> 0 Then
    Fail "构建 SQLite 后端", "Maven clean package 执行失败，退出码 " & CStr(exitCode) & "。"
End If

If Not fileSystem.FolderExists(fileSystem.BuildPath(appDir, "node_modules")) Then
    LogLine "[2/3] Installing frontend dependencies."
    exitCode = RunLogged("cd /d " & Quote(appDir) & " && call npm ci", True)
    If exitCode <> 0 Then
        Fail "安装前端依赖", "npm ci 执行失败，退出码 " & CStr(exitCode) & "。"
    End If
Else
    LogLine "[2/3] Frontend dependencies are present."
End If

LogLine "[3/3] Starting Electron."
exitCode = RunLogged("cd /d " & Quote(appDir) & " && call npm run dev", False)
If exitCode <> 0 Then
    Fail "启动 Electron", "无法创建前端开发进程，退出码 " & CStr(exitCode) & "。"
End If

' npm starts asynchronously. Do not treat an Electron process alone as ready:
' the app can open even when its Java backend failed. The launcher exits only
' after both the window process and the identified MindPet health endpoint exist.
electronSeen = False
For attempt = 1 To 180
    WScript.Sleep 500
    If IsWorkspaceElectronRunning() Then
        electronSeen = True
        If IsMindPetBackendHealthy() Then
            LogLine "MindPet started successfully; Electron and backend health are ready."
            WScript.Quit 0
        End If
    End If
    If attempt >= 4 And Not IsNpmLaunchRunning() Then
        Fail "启动 Electron", "npm run dev 已提前退出，MindPet 没有启动。"
    End If
Next

If electronSeen Then
    Fail "启动本地后端", "MindPet 窗口已经出现，但 SQLite 后端未就绪。请查看日志定位 Java 启动错误。"
Else
    Fail "启动 Electron", "等待 MindPet 窗口启动超时。"
End If

Sub EnsureFolder(ByVal folderPath)
    Dim parentPath
    If fileSystem.FolderExists(folderPath) Then Exit Sub
    parentPath = fileSystem.GetParentFolderName(folderPath)
    If Len(parentPath) > 0 And Not fileSystem.FolderExists(parentPath) Then
        EnsureFolder parentPath
    End If
    fileSystem.CreateFolder folderPath
End Sub

Sub ResetLog()
    Dim stream
    Set stream = fileSystem.CreateTextFile(logPath, True, False)
    stream.WriteLine "============================================================"
    stream.WriteLine "MindPet launcher - " & CStr(Now)
    stream.WriteLine "============================================================"
    stream.Close
End Sub

Sub LogLine(ByVal text)
    Dim stream
    On Error Resume Next
    Set stream = fileSystem.OpenTextFile(logPath, 8, True, 0)
    stream.WriteLine "[" & CStr(Now) & "] " & text
    stream.Close
    On Error GoTo 0
End Sub

Sub Fail(ByVal stepName, ByVal detail)
    LogLine "ERROR in " & stepName & ": " & detail
    MsgBox "MindPet 启动失败" & vbCrLf & vbCrLf & _
        "失败步骤：" & stepName & vbCrLf & _
        detail & vbCrLf & vbCrLf & _
        "详细日志：" & logPath, _
        vbCritical, "MindPet"
    WScript.Quit 1
End Sub

Sub CheckTool(ByVal commandName, ByVal displayName)
    Dim code
    LogLine "Checking " & displayName & "."
    code = RunLogged("where.exe " & commandName, True)
    If code <> 0 Then
        Fail "环境检查", "未找到 " & displayName & "。请安装并加入 PATH 后重试。"
    End If
End Sub

Sub StopOldBackend()
    Dim processes, process, commandLine, terminateCode, stoppedCount
    stoppedCount = 0
    Set processes = processService.ExecQuery( _
        "SELECT ProcessId, CommandLine FROM Win32_Process " & _
        "WHERE Name='java.exe' OR Name='javaw.exe'")

    For Each process In processes
        commandLine = SafeCommandLine(process)
        If InStr(1, commandLine, backendJar, vbTextCompare) > 0 Then
            On Error Resume Next
            Err.Clear
            terminateCode = process.Terminate()
            If Err.Number <> 0 Or terminateCode <> 0 Then
                Dim terminateError
                terminateError = Err.Description
                Err.Clear
                On Error GoTo 0
                Fail "停止旧后端", "无法终止占用后端 JAR 的 Java 进程。" & terminateError
            End If
            On Error GoTo 0
            stoppedCount = stoppedCount + 1
        End If
    Next

    If stoppedCount > 0 Then
        LogLine "Stopped " & CStr(stoppedCount) & " old backend process(es)."
        WScript.Sleep 800
    End If

    If IsBackendJarRunning() Then
        Fail "停止旧后端", "后端 JAR 仍被 Java 进程占用。"
    End If
End Sub

Function IsBackendJarRunning()
    Dim processes, process
    IsBackendJarRunning = False
    Set processes = processService.ExecQuery( _
        "SELECT CommandLine FROM Win32_Process " & _
        "WHERE Name='java.exe' OR Name='javaw.exe'")
    For Each process In processes
        If InStr(1, SafeCommandLine(process), backendJar, vbTextCompare) > 0 Then
            IsBackendJarRunning = True
            Exit Function
        End If
    Next
End Function

Function IsWorkspaceDevRunning()
    Dim processes, process, processName, commandLine, electronPath
    IsWorkspaceDevRunning = False
    electronPath = fileSystem.BuildPath(appDir, "node_modules\electron\dist\electron.exe")
    Set processes = processService.ExecQuery( _
        "SELECT Name, CommandLine FROM Win32_Process " & _
        "WHERE Name='electron.exe' OR Name='node.exe'")

    For Each process In processes
        processName = LCase(CStr(process.Name))
        commandLine = SafeCommandLine(process)
        If processName = "electron.exe" Then
            If InStr(1, commandLine, electronPath, vbTextCompare) > 0 Then
                IsWorkspaceDevRunning = True
                Exit Function
            End If
        ElseIf processName = "node.exe" Then
            If InStr(1, commandLine, appDir, vbTextCompare) > 0 And _
               InStr(1, commandLine, "electron-vite", vbTextCompare) > 0 Then
                IsWorkspaceDevRunning = True
                Exit Function
            End If
        End If
    Next
End Function

Function IsWorkspaceElectronRunning()
    Dim processes, process, commandLine, electronPath
    IsWorkspaceElectronRunning = False
    electronPath = fileSystem.BuildPath(appDir, "node_modules\electron\dist\electron.exe")
    Set processes = processService.ExecQuery( _
        "SELECT CommandLine FROM Win32_Process WHERE Name='electron.exe'")

    For Each process In processes
        commandLine = SafeCommandLine(process)
        If InStr(1, commandLine, electronPath, vbTextCompare) > 0 Then
            IsWorkspaceElectronRunning = True
            Exit Function
        End If
    Next
End Function

Function IsMindPetBackendHealthy()
    Dim request, responseText
    IsMindPetBackendHealthy = False
    On Error Resume Next
    Err.Clear
    Set request = CreateObject("WinHttp.WinHttpRequest.5.1")
    request.SetTimeouts 500, 500, 500, 500
    request.Open "GET", healthUrl, False
    request.Send
    If Err.Number = 0 And request.Status = 200 Then
        responseText = LCase(CStr(request.ResponseText))
        If InStr(responseText, "mindpet-desktop-api") > 0 Then
            IsMindPetBackendHealthy = True
        End If
    End If
    Err.Clear
    Set request = Nothing
    On Error GoTo 0
End Function

Function IsAnotherLauncherRunning()
    Dim processes, process, commandLine, launcherCount
    launcherCount = 0
    Set processes = processService.ExecQuery( _
        "SELECT CommandLine FROM Win32_Process " & _
        "WHERE Name='wscript.exe' OR Name='cscript.exe'")
    For Each process In processes
        commandLine = SafeCommandLine(process)
        If InStr(1, commandLine, scriptPath, vbTextCompare) > 0 Then
            launcherCount = launcherCount + 1
        End If
    Next
    IsAnotherLauncherRunning = (launcherCount > 1)
End Function

Function IsNpmLaunchRunning()
    Dim processes, process, commandLine
    IsNpmLaunchRunning = False
    Set processes = processService.ExecQuery( _
        "SELECT CommandLine FROM Win32_Process " & _
        "WHERE Name='cmd.exe' OR Name='node.exe'")
    For Each process In processes
        commandLine = SafeCommandLine(process)
        If InStr(1, commandLine, appDir, vbTextCompare) > 0 And _
           (InStr(1, commandLine, "npm run dev", vbTextCompare) > 0 Or _
            InStr(1, commandLine, "electron-vite", vbTextCompare) > 0) Then
            IsNpmLaunchRunning = True
            Exit Function
        End If
    Next
End Function

Function SafeCommandLine(ByVal process)
    If IsNull(process.CommandLine) Then
        SafeCommandLine = ""
    Else
        SafeCommandLine = CStr(process.CommandLine)
    End If
End Function

Function RunLogged(ByVal command, ByVal waitForExit)
    Dim commandLine, code, runError
    commandLine = shell.ExpandEnvironmentStrings("%ComSpec%") & _
        " /d /s /c " & Quote("chcp 65001 >nul & " & command & _
        " >> " & Quote(logPath) & " 2>&1")

    On Error Resume Next
    Err.Clear
    code = shell.Run(commandLine, 0, waitForExit)
    If Err.Number <> 0 Then
        runError = Err.Description
        Err.Clear
        On Error GoTo 0
        LogLine "Unable to create hidden command: " & runError
        RunLogged = -1
        Exit Function
    End If
    On Error GoTo 0
    RunLogged = code
End Function

Function Quote(ByVal value)
    Quote = Chr(34) & value & Chr(34)
End Function
