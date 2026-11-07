package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import org.assertj.core.api.Assertions.assertThat
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class MediaProxyJvm(
    private val directory: Path,
) {
    fun run(
        settings: Map<String, String>,
        arguments: List<String>,
    ) {
        val output = directory.resolve("probe-output.txt").toFile()
        val command =
            listOf("${System.getProperty("java.home")}/bin/java", "-cp", classpath(), MediaProxyJvmProbe::class.java.name) + arguments
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output)
        // 환경에서 상속된 JVM 옵션은 테스트에 영향을 주거나 외부 프록시로 연결하지 못하게 한다.
        builder.environment().remove("JDK_JAVA_OPTIONS")
        builder.environment().remove("_JAVA_OPTIONS")
        builder.environment()["JAVA_TOOL_OPTIONS"] = settings.entries.joinToString(" ") { "-D${it.key}=${it.value}" }
        val process = builder.start()
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).describedAs("child JVM must finish: %s", output.readText()).isTrue()
            assertThat(process.exitValue()).describedAs(output.readText()).isZero()
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun classpath(): String {
        val loaderPaths =
            generateSequence(javaClass.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }
                .map { File(it.toURI()).path }
        return (loaderPaths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
    }
}
