# 如果你有已编译好的 libzt 原生库（.so），可以用此脚本快速创建 .aar 包

# 创建 .aar 结构
AAR_DIR="libzt-aar-temp"
rm -rf $AAR_DIR
mkdir -p $AAR_DIR/jni/arm64-v8a
mkdir -p $AAR_DIR/jni/armeabi-v7a
mkdir -p $AAR_DIR/jni/x86_64

# 复制 .so 文件（需要自行准备好各架构的 libzt.so）
# cp path/to/arm64-v8a/libzt.so $AAR_DIR/jni/arm64-v8a/
# cp path/to/armeabi-v7a/libzt.so $AAR_DIR/jni/armeabi-v7a/
# cp path/to/x86_64/libzt.so $AAR_DIR/jni/x86_64/

# 创建 AndroidManifest（最小化）
cat > $AAR_DIR/AndroidManifest.xml << 'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.zerotier.libzt">
</manifest>
EOF

# 打包
cd $AAR_DIR
zip -r ../libzt.aar .
cd ..
rm -rf $AAR_DIR

echo "libzt.aar 已生成，请放入 app/libs/ 目录"
