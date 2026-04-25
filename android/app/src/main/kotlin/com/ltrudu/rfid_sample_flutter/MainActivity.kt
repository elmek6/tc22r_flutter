Launching lib\main.dart on TC22 in debug mode...

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if "Windows_NT" == "Windows_NT" setlocal

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set DEFAULT_JVM_OPTS=

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set DIRNAME=C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android\

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if "C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android\" == "" set DIRNAME=.

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set APP_BASE_NAME=gradlew

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set APP_HOME=C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android\

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if defined JAVA_HOME goto findJavaFromJavaHome

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set JAVA_EXE=C:\Program Files\Android\Android Studio\jbr/bin/java.exe

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if exist "C:\Program Files\Android\Android Studio\jbr/bin/java.exe" goto init

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if not "Windows_NT" == "Windows_NT" goto win9xME_args

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if "@eval[2+2]" == "4" goto 4NT_args

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set CMD_LINE_ARGS=

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set _SKIP=2

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if "x-q" == "x" goto execute

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set CMD_LINE_ARGS=-q -Ptarget-platform=android-arm64 -Ptarget=C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\lib\main.dart -Pbase-application-name=android.app.Application -Pdart-defines=RkxVVFRFUl9WRVJTSU9OPTMuMjkuMg==,RkxVVFRFUl9DSEFOTkVMPXN0YWJsZQ==,RkxVVFRFUl9HSVRfVVJMPWh0dHBzOi8vZ2l0aHViLmNvbS9mbHV0dGVyL2ZsdXR0ZXIuZ2l0,RkxVVFRFUl9GUkFNRVdPUktfUkVWSVNJT049YzIzNjM3MzkwNA==,RkxVVFRFUl9FTkdJTkVfUkVWSVNJT049ZDNkNDVkY2YyNQ==,RkxVVFRFUl9EQVJUX1ZFUlNJT049My45LjI= -Pdart-obfuscation=false -Ptrack-widget-creation=true -Ptree-shake-icons=false -Pfilesystem-scheme=org-dartlang-root assembleDebug

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>goto execute
package com.ltrudu.rfid_sample_flutter

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.os.Bundle
import android.util.Log

class MainActivity: FlutterActivity() {
       private val CHANNEL = "com.ltrudu.rfid_sample_flutter/native"
       private lateinit var dataWedgeHandler: DataWedgeHandler
       private var methodChannel: MethodChannel? = null
   
       override fun onCreate(savedInstanceState: Bundle?) {
                  super.onCreate(savedInstanceState)
                  Log.d("MainActivity", "onCreate called")
                  
                  // Initialize DataWedgeHandler
                  dataWedgeHandler = DataWedgeHandler(this)
       }
   
       override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
                  super.configureFlutterEngine(flutterEngine)
                  
                  // Setup method channel for communication with Flutter
                  methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                  methodChannel?.setMethodCallHandler { call, result ->
                                 when (call.method) {
                                                    "enableScanning" -> {
                                                                           Log.d("MainActivity", "enableScanning called")
                                                                           dataWedgeHandler.enableScanning()
                                                                           result.success("Scanning enabled")
                                                    }
                                                    "disableScanning" -> {
                                                                           Log.d("MainActivity", "disableScanning called")
                                                                           dataWedgeHandler.disableScanning()
                                                                           result.success("Scanning disabled")
                                                    }
                                                    else -> {
                                                                           result.notImplemented()
                                                    }
                                 }
                  }
                  
                  // Set callback for barcode data
                  dataWedgeHandler.setBarcodeCallback { barcodeData ->
                                 methodChannel?.invokeMethod("onBarcodeScanned", barcodeData)
                  }
       }
   
       override fun onResume() {
                  super.onResume()
                  Log.d("MainActivity", "onResume called")
                  dataWedgeHandler.onResume()
       }
   
       override fun onPause() {
                  super.onPause()
                  Log.d("MainActivity", "onPause called")
                  dataWedgeHandler.onPause()
       }
   
       override fun onDestroy() {
                  super.onDestroy()
                  Log.d("MainActivity", "onDestroy called")
                  dataWedgeHandler.onDestroy()
       }
}C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>set CLASSPATH=C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android\\gradle\wrapper\gradle-wrapper.jar

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>"C:\Program Files\Android\Android Studio\jbr/bin/java.exe"    "-Dorg.gradle.appname=gradlew" -classpath "C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android\\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain -q -Ptarget-platform=android-arm64 -Ptarget=C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\lib\main.dart -Pbase-application-name=android.app.Application -Pdart-defines=RkxVVFRFUl9WRVJTSU9OPTMuMjkuMg==,RkxVVFRFUl9DSEFOTkVMPXN0YWJsZQ==,RkxVVFRFUl9HSVRfVVJMPWh0dHBzOi8vZ2l0aHViLmNvbS9mbHV0dGVyL2ZsdXR0ZXIuZ2l0,RkxVVFRFUl9GUkFNRVdPUktfUkVWSVNJT049YzIzNjM3MzkwNA==,RkxVVFRFUl9FTkdJTkVfUkVWSVNJT049ZDNkNDVkY2YyNQ==,RkxVVFRFUl9EQVJUX1ZFUlNJT049My45LjI= -Pdart-obfuscation=false -Ptrack-widget-creation=true -Ptree-shake-icons=false -Pfilesystem-scheme=org-dartlang-root assembleDebug
e: file:///C:/DEPO-1/FIXED/pl/flutter/src_flutter/tc22r_flutter/android/app/src/main/kotlin/com/ltrudu/rfid_sample_flutter/DataWedgeHandler.kt:20:7 Redeclaration:
class DataWedgeHandler : Any
e: file:///C:/DEPO-1/FIXED/pl/flutter/src_flutter/tc22r_flutter/android/app/src/main/kotlin/com/ltrudu/rfid_sample_flutter/MainActivity.kt:16:7 Redeclaration:
class DataWedgeHandler : Any

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 39s

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if "1" == "0" goto mainEnd

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>rem the _cmd.exe /c_ return code!

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>if not "" == "" exit 1

C:\DEPO-1\FIXED\pl\flutter\src_flutter\tc22r_flutter\android>exit /b 1
Error: Gradle task assembleDebug failed with exit code 1

Exited (1).
