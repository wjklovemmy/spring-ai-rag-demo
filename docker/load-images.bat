@echo off
REM 离线环境导入 Docker 基础镜像
REM 用法: 双击运行 或 load-images.bat

echo === 开始导入基础镜像 ===

echo 导入: etcd-v3.5.18.tar
docker load -i "%~dp0images\etcd-v3.5.18.tar"
if %errorlevel% neq 0 (
    echo 错误: etcd 导入失败
    pause
    exit /b 1
)

echo 导入: minio-RELEASE.2024-12-18T13-15-44Z.tar
docker load -i "%~dp0images\minio-RELEASE.2024-12-18T13-15-44Z.tar"
if %errorlevel% neq 0 (
    echo 错误: minio 导入失败
    pause
    exit /b 1
)

echo 导入: milvus-v2.6.0.tar
docker load -i "%~dp0images\milvus-v2.6.0.tar"
if %errorlevel% neq 0 (
    echo 错误: milvus 导入失败
    pause
    exit /b 1
)

echo 导入: attu-latest.tar
docker load -i "%~dp0images\attu-latest.tar"
if %errorlevel% neq 0 (
    echo 错误: attu 导入失败
    pause
    exit /b 1
)

echo 导入: minio-mc-latest.tar
docker load -i "%~dp0images\minio-mc-latest.tar"
if %errorlevel% neq 0 (
    echo 错误: minio/mc 导入失败
    pause
    exit /b 1
)

echo === 全部导入完成 ===
docker images | findstr "etcd minio milvus attu"
pause
