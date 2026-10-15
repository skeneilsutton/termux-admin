# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# Temp fix for androidx.window:window:1.0.0-alpha09 imported by termux-shared
# https://issuetracker.google.com/issues/189001730
# https://android-review.googlesource.com/c/platform/frameworks/support/+/1757630
-keep class androidx.window.** { *; }

# app_process entry points do not use the Android component lifecycle.
-keep class com.termux.admin.Cli { *; }
-keep class com.termux.admin.ShellService { *; }
-keep class com.termux.admin.MaintenanceCli { *; }
-keep class com.termux.admin.Admin { *; }
-keep class com.termux.admin.Admin$* { *; }
