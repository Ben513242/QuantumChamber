@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

rem 僅接受明確的選用照明參數；無參數保留原始客戶端。
rem 保留原始 token 的引號：明確傳入的 "" 也算一個參數。
set "CLIENT_TASK=runClient"
if not [%2]==[] goto usageFailure
if [%1]==[] goto startClient
if /i not "%~1"=="light" goto usageFailure
set "CLIENT_TASK=runClientLight"

:startClient

echo 啟動開發客戶端需要 Java 21；首次執行 Gradle 可能需要準備相依套件。
pushd "%~dp0"
if errorlevel 1 goto directoryFailure

rem 優先保留 JAVA_HOME；未設定時只尋找已安裝的 Adoptium Java 21。
if not defined JAVA_HOME (
    for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do (
        if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
    )
)

rem 由 Gradle 判斷 JAVA_HOME 或 PATH Java；call 確保 wrapper 結束後返回。
call "%~dp0gradlew.bat" --no-daemon %CLIENT_TASK%
set "CLIENT_EXIT_CODE=%ERRORLEVEL%"
popd
if not "%CLIENT_EXIT_CODE%"=="0" (
    echo 客戶端啟動或執行失敗，錯誤碼：%CLIENT_EXIT_CODE%。請查看上方錯誤並確認 Java 21。
    pause
)
endlocal & exit /b %CLIENT_EXIT_CODE%

:usageFailure
echo 用法：start-client.bat [light]；無參數啟動一般客戶端，light 啟動選用手持照明。
endlocal & exit /b 2

:directoryFailure
echo 無法進入啟動腳本所在目錄，啟動失敗。
pause
endlocal & exit /b 1
