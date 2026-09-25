# Keep the DeviceAdminReceiver and services referenced from the manifest and
# from ADB provisioning (dpm set-device-owner uses the fully-qualified name).
-keep class com.personal.guardian.admin.** { *; }
-keep class com.personal.guardian.service.** { *; }
-keep class com.personal.guardian.vpn.** { *; }
-keep class com.personal.guardian.boot.** { *; }
