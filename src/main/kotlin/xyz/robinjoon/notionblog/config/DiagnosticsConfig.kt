package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.adapter.outbound.diagnostics.Slf4jSnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter

@Configuration(proxyBeanMethods = false)
internal class DiagnosticsConfig {
    @Bean
    fun snapshotFailureReporter(): SnapshotFailureReporter = Slf4jSnapshotFailureReporter()
}
