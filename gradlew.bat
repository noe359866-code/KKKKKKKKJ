@rem Lanzador mínimo del Gradle Wrapper (ver gradlew).
@echo off
setlocal

set APP_HOME=%~dp0
if defined JAVA_HOME (
    set JAVA_BIN=%JAVA_HOME%\bin\java.exe
) else (
    set JAVA_BIN=java.exe
)

"%JAVA_BIN%" -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
set EXIT_CODE=%ERRORLEVEL%

endlocal & exit /b %EXIT_CODE%
