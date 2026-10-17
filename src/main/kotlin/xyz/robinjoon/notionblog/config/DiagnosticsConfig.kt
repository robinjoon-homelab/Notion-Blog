package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import xyz.robinjoon.notionblog.blog.adapter.outbound.diagnostics.Slf4jOperationFailureReporter
import xyz.robinjoon.notionblog.blog.adapter.outbound.diagnostics.Slf4jSnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter

@Configuration(proxyBeanMethods = false)
internal class DiagnosticsConfig {
    @Bean
    fun snapshotFailureReporter(): SnapshotFailureReporter = Slf4jSnapshotFailureReporter()

    @Bean
    fun operationFailureReporter(): OperationFailureReporter = Slf4jOperationFailureReporter()
}
