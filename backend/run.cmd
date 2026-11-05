@echo off
rem 启动后端：自动加载 .env 里的模型配置
cd /d %~dp0
if exist .env (
  for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do (
    if not "%%a"=="" set "%%a=%%b"
  )
)
if "%APP_PORT%"=="" set APP_PORT=8080
echo 启动后端 port=%APP_PORT% model=%LLM_MODEL%
call mvnw.cmd -q spring-boot:run -Dspring-boot.run.arguments=--server.port=%APP_PORT%
