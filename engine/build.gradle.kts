import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

// برای اینکه D8 اندروید راحت باشد، بایت‌کد موتور روی Java 11 هدف می‌گیرد
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    // جاروب پارامتر روی دیتاست ده‌ساله (۳٫۴ میلیون کندل) به هیپ بزرگ‌تری نیاز دارد؛
    // چون دستگاه کم‌حافظه است، اندازهٔ هیپ از بیرون تزریق می‌شود نه ثابت.
    maxHeapSize = System.getenv("SWEEP_HEAP") ?: "512m"
}
