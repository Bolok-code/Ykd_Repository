@echo off
setlocal

set "JAVA_EXE="
for /d %%D in ("%ProgramFiles%\Java\jdk-21*") do (
    if exist "%%~fD\bin\java.exe" set "JAVA_EXE=%%~fD\bin\java.exe"
)
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_EXE set "JAVA_EXE=java"

set "JAVA_VERSION="
for /f "tokens=3" %%V in ('^""%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"^"') do (
    if not defined JAVA_VERSION set "JAVA_VERSION=%%~V"
)
set "JAVA_MAJOR="
for /f "tokens=1 delims=." %%M in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%M"
if not defined JAVA_MAJOR goto java_error
if %JAVA_MAJOR% LSS 21 goto java_error

pushd "%~dp0"
set "TARGET=%CD%\target\cli-toolbox-0.1.0.jar"
if not exist "%TARGET%" (
    echo [错误] 尚未找到可执行 Jar，请先运行 mvn clean package。
    popd
    exit /b 1
)
"%JAVA_EXE%" -jar "%TARGET%" %*
set "APP_EXIT_CODE=%errorlevel%"
popd
exit /b %APP_EXIT_CODE%

:java_error
echo [错误] 本项目需要 JDK 21 或更高版本。
echo 请设置 JAVA_HOME 后重新打开终端。当前检测版本: %JAVA_VERSION%
exit /b 1
