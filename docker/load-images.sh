#!/bin/bash
# 离线环境导入 Docker 基础镜像
# 用法: chmod +x load-images.sh && ./load-images.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGES_DIR="$SCRIPT_DIR/images"

echo "=== 开始导入基础镜像 ==="

images=(
    "etcd-v3.5.18.tar"
    "minio-RELEASE.2024-12-18T13-15-44Z.tar"
    "milvus-v2.6.0.tar"
    "attu-latest.tar"
    "minio-mc-latest.tar"
)

for img in "${images[@]}"; do
    echo "导入: $img"
    docker load -i "$IMAGES_DIR/$img"
    if [ $? -ne 0 ]; then
        echo "错误: $img 导入失败"
        exit 1
    fi
done

echo "=== 全部导入完成 ==="
docker images | grep -E "etcd|minio|milvus|attu|minio/mc"
