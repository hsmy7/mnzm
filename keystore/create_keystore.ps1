$kt = "C:\Program Files\Android\Android Studio1\jbr\bin\keytool.exe"
& $kt -genkeypair -v -keystore "c:\Mnzm\XianxiaSectNative\keystore\keystore.jks" -alias xianxia_sect -keyalg RSA -keysize 2048 -validity 10000 -storepass xianxia2024 -keypass xianxia2024 -dname "CN=XianxiaSect,O=Xianxia,L=Beijing,C=CN"
