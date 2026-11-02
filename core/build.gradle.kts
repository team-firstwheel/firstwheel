// 차량 SDK에 의존하지 않는 순수 Kotlin 모듈.
// 운전 점수, 차선 변경 타이밍, 주차 코치 알고리즘을 담고 있어 PC에서 바로 단위 테스트할 수 있다.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
