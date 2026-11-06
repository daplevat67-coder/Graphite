import java.security.MessageDigest
import groovy.json.JsonSlurper
plugins { id("com.android.library") }
android {
 namespace = "art.graphite.xray"
 compileSdk = 35
 defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(files("src/main/libs/libXray-classes.jar", "src/main/libs/hev-socks5-tunnel-classes.jar")) }
val verifyXrayNative by tasks.registering {
 doLast {
  val entries=JsonSlurper().parse(file("native-sha256.json")) as Map<*,*>
  check(entries.size==10) { "Expected Xray/Hev JNI four ABIs and two binding JARs" }
  for((path,expected) in entries) {
   val binary=file(path.toString());check(binary.isFile) { "Missing pinned Xray native artifact" }
   val digest=MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
   check(digest==expected.toString()) { "Xray native artifact hash mismatch" }
  }
 }
}
tasks.named("preBuild").configure { dependsOn(verifyXrayNative) }
