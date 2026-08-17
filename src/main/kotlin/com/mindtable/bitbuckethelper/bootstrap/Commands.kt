package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.mindtable.bitbuckethelper.cli.ProductCommandDependencies
import com.mindtable.bitbuckethelper.cli.productCommands
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private const val APPLICATION_VERSION = "0.1.0"

private class BitbucketHelperCommand : CliktCommand(name = "bitbucket-helper") {
    init { versionOption(APPLICATION_VERSION) }
    override fun run() = Unit
}

private class ServiceCommand : CliktCommand(name = "service") {
    override fun run() = Unit
}

private class ServiceRunCommand(
    private val runService: () -> Unit,
) : CliktCommand(name = "run") {
    override fun run() = runService()
}

fun rootCommand(
    runService: () -> Unit,
    productDependencies: ProductCommandDependencies,
): CliktCommand =
    BitbucketHelperCommand().subcommands(
        ServiceCommand().subcommands(ServiceRunCommand(runService)),
        *productCommands(productDependencies).toTypedArray(),
    )

fun runConfiguredService(environment: Map<String, String> = System.getenv()) {
    val shutdown = CountDownLatch(1)
    val closed = AtomicBoolean()
    var runtime: ServiceRuntime? = null
    val closeOnce = {
        if (closed.compareAndSet(false, true)) {
            runtime?.close()
        }
    }
    val shutdownHook = Thread(
        {
            try {
                closeOnce()
            } finally {
                shutdown.countDown()
            }
        },
        "bitbucket-helper-shutdown",
    )
    var hookInstalled = false

    try {
        val activeRuntime = loadAndCreateRuntime(
            config = ConfigFactory.load("application.conf"),
            environment = environment,
            create = ServiceRuntime::create,
        )
        runtime = activeRuntime
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        hookInstalled = true
        activeRuntime.start()
        shutdown.await()
    } finally {
        if (hookInstalled) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
        closeOnce()
    }
}

internal fun <T> loadAndCreateRuntime(
    config: Config,
    environment: Map<String, String>,
    create: (ServiceConfiguration) -> T,
): T = create(ServiceConfigurationLoader.load(config, environment))
