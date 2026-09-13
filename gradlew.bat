@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  gradlew startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem Setup the command line

@rem ---------------------------------------------------------------------------
@rem 내부망(폐쇄망) 분기 - 이 프로젝트 추가분. 자세한 설명은 gradlew(sh) 쪽 주석 참고.
@rem   1) TESTER_GRADLE_DIST_URL 환경변수  2) gradle.properties 의 gradleDistUrl  3) 없으면 기본
@rem 커밋된 gradle-wrapper.properties 는 건드리지 않고 build\wrapper 에 사본을 만든다.
@rem ---------------------------------------------------------------------------
set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
set TESTER_DIST_URL=%TESTER_GRADLE_DIST_URL%
if not defined TESTER_DIST_URL (
    if exist "%APP_HOME%\gradle.properties" (
        for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /r /c:"[ ]*gradleDistUrl[ ]*=" "%APP_HOME%\gradle.properties"`) do (
            if not defined TESTER_DIST_URL set TESTER_DIST_URL=%%b
        )
    )
)
if defined TESTER_DIST_URL (
    if not exist "%APP_HOME%\build\wrapper" mkdir "%APP_HOME%\build\wrapper" >NUL 2>&1
    if exist "%APP_HOME%\build\wrapper" (
        copy /y "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" "%APP_HOME%\build\wrapper\gradle-wrapper.jar" >NUL 2>&1
        > "%APP_HOME%\build\wrapper\gradle-wrapper.properties" (
            for /f "usebackq delims=" %%l in ("%APP_HOME%\gradle\wrapper\gradle-wrapper.properties") do (
                echo %%l| findstr /b /c:"distributionUrl=" >NUL && (echo distributionUrl=%TESTER_DIST_URL%) || (echo %%l)
            )
        )
        set WRAPPER_JAR=%APP_HOME%\build\wrapper\gradle-wrapper.jar
    )
)



@rem Execute gradlew
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%WRAPPER_JAR%" %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
