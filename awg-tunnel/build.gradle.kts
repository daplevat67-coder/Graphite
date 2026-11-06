import java.security.MessageDigest
plugins { id("com.android.library") }
android {
 namespace = "org.amnezia.awg.tunnel"
 compileSdk = 35
 defaultConfig { minSdk = 26 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 lint { disable += "NewApi"; disable += "LongLogTag" }
}
dependencies {
 implementation("androidx.annotation:annotation:1.9.1")
 implementation("androidx.collection:collection:1.4.5")
 compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
val verifyAwgNative by tasks.registering {
 doLast {
  val manifest = file("native-sha256.json")
  check(manifest.isFile) { "Missing AWG native hashes. See awg-tunnel/UPSTREAM.md" }
  val entries = manifest.readLines().mapNotNull { line -> val parts=line.split('"'); if(parts.size>=4) parts[1] to parts[3] else null }
  check(entries.size == 4) { "Expected four native ABIs" }
  for(e in entries) {
   val f=file(e.first);check(f.isFile) { "Missing AWG JNI binary" }
   check(MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)} == e.second) { "AWG native hash mismatch" }
  }
 }
}
tasks.named("preBuild").configure { dependsOn(verifyAwgNative) }
