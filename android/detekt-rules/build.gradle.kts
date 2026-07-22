plugins {
    kotlin("jvm")
    id("io.gitlab.arturbosch.detekt") apply false
}

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.7")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
}
